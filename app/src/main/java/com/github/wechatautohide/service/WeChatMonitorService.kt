package com.github.wechatautohide.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.github.wechatautohide.MainActivity
import com.github.wechatautohide.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WeChatMonitorService : AccessibilityService() {

    private val TAG = "WeChatMonitor"
    private val WECHAT_PACKAGE = "com.tencent.mm"

    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var isProcessing = false
    private var lastCheckTime = 0L
    private val CHECK_INTERVAL = 1500L
    private val processedMap = mutableMapOf<String, Long>()
    private val PROCESS_COOLDOWN = 30000L

    private fun log(msg: String) {
        Log.d(TAG, msg)
        MainActivity.addLog(msg)
    }

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        log("✅ 服务已启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != WECHAT_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL) return
        if (isProcessing) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastCheckTime = now
                serviceScope.launch { scanWeChat() }
            }
        }
    }

    // ========== 核心扫描 ==========

    private suspend fun scanWeChat() {
        if (isProcessing) return
        isProcessing = true

        try {
            val contacts = withContext(Dispatchers.IO) {
                database.hideContactDao().getAllContactsSync()
            }.filter { it.enabled }

            if (contacts.isEmpty()) return

            val hideNames = contacts.map { it.name.trim() }.toSet()

            val rootNode = rootInActiveWindow ?: run {
                log("根节点为空")
                return
            }

            try {
                val pkg = rootNode.packageName?.toString() ?: ""
                if (pkg != WECHAT_PACKAGE) {
                    return
                }

                // 收集所有文本节点（不依赖ViewId）
                val textNodes = mutableListOf<NodeInfo>()
                collectTextNodes(rootNode, textNodes, 0)

                log("扫描到 ${textNodes.size} 个文本节点")

                // 打印前20个节点内容用于调试
                textNodes.take(20).forEach { ni ->
                    log("  [${ni.depth}] '${ni.text}' cls=${ni.className?.substringAfterLast('.')} lc=${ni.isLongClickable}")
                }

                // 查找目标联系人
                for (ni in textNodes) {
                    if (hideNames.contains(ni.text)) {
                        val now = System.currentTimeMillis()
                        if ((now - (processedMap[ni.text] ?: 0L)) < PROCESS_COOLDOWN) {
                            log("冷却中: ${ni.text}")
                            continue
                        }

                        log("🎯 匹配到: '${ni.text}'，执行隐藏")
                        processedMap[ni.text] = now

                        val success = performHide(ni.node)
                        if (success) {
                            contacts.find { it.name.trim() == ni.text }?.let { c ->
                                withContext(Dispatchers.IO) {
                                    database.hideContactDao().incrementHideCount(c.id)
                                }
                            }
                            log("✅ 已隐藏: ${ni.text}")
                        } else {
                            log("❌ 隐藏失败: ${ni.text}")
                            processedMap.remove(ni.text)
                        }
                        break // 每次只处理一个
                    }
                }

            } finally {
                rootNode.recycle()
            }

        } catch (e: Exception) {
            log("异常: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    // ========== 节点收集（不依赖ViewId）==========

    data class NodeInfo(
        val text: String,
        val className: String?,
        val isLongClickable: Boolean,
        val depth: Int,
        val node: AccessibilityNodeInfo
    )

    private fun collectTextNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<NodeInfo>,
        depth: Int
    ) {
        if (depth > 12) return

        val text = (node.text?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()
        val content = when {
            text.isNotEmpty() -> text
            desc.isNotEmpty() -> desc
            else -> ""
        }

        if (content.isNotEmpty()) {
            result.add(
                NodeInfo(
                    text = content,
                    className = node.className?.toString(),
                    isLongClickable = node.isLongClickable,
                    depth = depth,
                    node = node
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextNodes(child, result, depth + 1)
        }
    }

    // ========== 执行隐藏操作 ==========

    private suspend fun performHide(targetNode: AccessibilityNodeInfo): Boolean {
        // 第1步：找到可长按的祖先节点
        val longClickTarget = findLongClickable(targetNode)
        if (longClickTarget == null) {
            log("未找到可长按节点，尝试坐标长按")
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            if (rect.isEmpty) {
                log("节点边界为空")
                return false
            }
            return gestureAndHide(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        val rect = Rect()
        longClickTarget.getBoundsInScreen(rect)
        log("长按节点: ${longClickTarget.className} bounds=$rect")

        // 第2步：优先用 ACTION_LONG_CLICK
        val actionOk = longClickTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        log("ACTION_LONG_CLICK 结果: $actionOk")

        if (actionOk) {
            delay(1200)
            return clickMenu()
        }

        // 第3步：降级用手势长按
        if (!rect.isEmpty) {
            return gestureAndHide(rect.centerX().toFloat(), rect.centerY().toFloat())
        }

        return false
    }

    private suspend fun gestureAndHide(x: Float, y: Float): Boolean {
        log("手势长按: ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 900L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        delay(1300)
        return clickMenu()
    }

    private fun findLongClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isLongClickable) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 10) {
            if (parent.isLongClickable) return parent
            val gp = parent.parent
            if (depth > 0) parent.recycle()
            parent = gp
            depth++
        }
        return null
    }

    // ========== 点击菜单 ==========

    private fun clickMenu(): Boolean {
        val root = rootInActiveWindow ?: return false

        // 打印弹出菜单的所有文本
        val menuTexts = mutableListOf<String>()
        collectMenuTexts(root, menuTexts, 0)
        log("菜单内容: $menuTexts")

        val targets = listOf(
            "不显示该聊天",
            "不显示",
            "隐藏对话",
            "Delete",
            "Hide"
        )

        for (target in targets) {
            val nodes = root.findAccessibilityNodeInfosByText(target)
            if (nodes.isNullOrEmpty()) continue

            log("点击菜单项: $target")
            for (node in nodes) {
                if (clickNodeOrAncestor(node)) {
                    node.recycle()
                    root.recycle()
                    return true
                }
                node.recycle()
            }
        }

        root.recycle()
        performGlobalAction(GLOBAL_ACTION_BACK)
        log("未找到菜单项，已关闭")
        return false
    }

    private fun collectMenuTexts(
        node: AccessibilityNodeInfo,
        result: MutableList<String>,
        depth: Int
    ) {
        if (depth > 6) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) result.add(t)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMenuTexts(child, result, depth + 1)
            child.recycle()
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable) {
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                parent.recycle()
                return true
            }
            val gp = parent.parent
            parent.recycle()
            parent = gp
            depth++
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
