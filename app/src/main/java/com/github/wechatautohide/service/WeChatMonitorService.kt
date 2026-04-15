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
import com.github.wechatautohide.database.HideContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        val pkg = event.packageName?.toString() ?: ""

        // 只处理微信的事件
        if (pkg != WECHAT_PACKAGE) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL) return
        if (isProcessing) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastCheckTime = now
                // 直接在事件线程触发扫描
                serviceScope.launch {
                    scanWeChat()
                }
            }
        }
    }

    private suspend fun scanWeChat() {
        if (isProcessing) return
        isProcessing = true

        try {
            val contacts = database.hideContactDao().getAllContactsSync()
                .filter { it.enabled }
            if (contacts.isEmpty()) {
                isProcessing = false
                return
            }

            val hideNames = contacts.map { it.name.trim() }.toSet()

            // 直接获取当前窗口（必须在微信前台时）
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                log("窗口为空")
                isProcessing = false
                return
            }

            // 确认是微信界面
            val pkg = rootNode.packageName?.toString() ?: ""
            if (pkg != WECHAT_PACKAGE) {
                log("当前不是微信界面: $pkg")
                rootNode.recycle()
                isProcessing = false
                return
            }

            log("扫描微信界面，名单: $hideNames")

            // 打印所有有ViewId的节点
            val allNodes = mutableListOf<Triple<String, String, AccessibilityNodeInfo>>()
            collectAllNodes(rootNode, allNodes, 0)

            log("共找到 ${allNodes.size} 个有ViewId的节点")

            // 查找匹配的联系人
            for ((viewId, text, node) in allNodes) {
                val trimText = text.trim()
                if (hideNames.contains(trimText)) {
                    log("🎯 找到目标! ViewId='$viewId' text='$trimText'")

                    val now = System.currentTimeMillis()
                    if ((now - (processedMap[trimText] ?: 0)) < PROCESS_COOLDOWN) {
                        log("冷却中，跳过")
                        continue
                    }

                    val target = getLongClickTarget(node)
                    if (target != null) {
                        processedMap[trimText] = now
                        val ok = longClickAndHide(target)
                        target.recycle()
                        if (ok) {
                            contacts.find { it.name == trimText }?.let {
                                database.hideContactDao().incrementHideCount(it.id)
                            }
                            log("✅ 隐藏成功: $trimText")
                        }
                    }
                } else if (trimText.isNotEmpty()) {
                    // 打印所有非空文本节点供调试
                    log("节点: ViewId='$viewId' text='$trimText'")
                }
                node.recycle()
            }

            rootNode.recycle()

        } catch (e: Exception) {
            log("出错: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    private fun collectAllNodes(
        node: AccessibilityNodeInfo,
        result: MutableList<Triple<String, String, AccessibilityNodeInfo>>,
        depth: Int
    ) {
        if (depth > 10) return

        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val content = if (text.isNotEmpty()) text else desc

        if (viewId.isNotEmpty()) {
            result.add(Triple(viewId, content, node))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllNodes(child, result, depth + 1)
        }
    }

    private fun getLongClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isLongClickable) return node
        var p = node.parent
        var depth = 0
        while (p != null && depth < 10) {
            if (p.isLongClickable) return p
            val gp = p.parent
            if (depth > 0) p.recycle()
            p = gp
            depth++
        }
        return null
    }

    private suspend fun longClickAndHide(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false

        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()
        log("长按: ($x, $y)")

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 800)
        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            null, null
        )

        delay(1200)
        return clickMenu()
    }

    private fun clickMenu(): Boolean {
        val root = rootInActiveWindow ?: return false

        val options = listOf(
            "不显示该聊天",
            "不显示",
            "Delete",
            "隐藏对话"
        )

        for (text in options) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            if (nodes.isNullOrEmpty()) continue

            log("找到菜单: $text")
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    root.recycle()
                    return true
                }
                var p = node.parent
                var d = 0
                while (p != null && d < 5) {
                    if (p.isClickable) {
                        p.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        p.recycle()
                        node.recycle()
                        root.recycle()
                        return true
                    }
                    val gp = p.parent
                    p.recycle()
                    p = gp
                    d++
                }
                node.recycle()
            }
        }

        root.recycle()
        performGlobalAction(GLOBAL_ACTION_BACK)
        log("未找到菜单")
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
