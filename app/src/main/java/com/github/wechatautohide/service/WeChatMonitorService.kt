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

            val rootNode = rootInActiveWindow ?: return

            try {
                if (rootNode.packageName?.toString() != WECHAT_PACKAGE) return

                // ===== 调试：打印完整节点树 =====
                val dump = StringBuilder()
                dumpNodeTree(rootNode, dump, 0)
                val dumpStr = dump.toString()

                // 分段打印（避免日志截断）
                val lines = dumpStr.lines()
                log("节点树共 ${lines.size} 行：")
                lines.chunked(10).forEachIndexed { i, chunk ->
                    log("== 第${i+1}段 ==\n${chunk.joinToString("\n")}")
                }

                // ===== 用所有可能方式查找文本 =====
                val allTexts = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
                collectAllPossibleTexts(rootNode, allTexts, 0)

                log("所有可读文本 (${allTexts.size} 个):")
                allTexts.forEach { (text, _) -> log("  '$text'") }

                // 匹配联系人
                for ((text, node) in allTexts) {
                    if (hideNames.contains(text)) {
                        val now = System.currentTimeMillis()
                        if ((now - (processedMap[text] ?: 0L)) < PROCESS_COOLDOWN) {
                            log("冷却中: $text")
                            continue
                        }
                        log("🎯 匹配: '$text'")
                        processedMap[text] = now

                        val ok = performHide(node)
                        if (ok) {
                            contacts.find { it.name.trim() == text }?.let { c ->
                                withContext(Dispatchers.IO) {
                                    database.hideContactDao().incrementHideCount(c.id)
                                }
                            }
                            log("✅ 隐藏成功: $text")
                        } else {
                            processedMap.remove(text)
                            log("❌ 隐藏失败: $text")
                        }
                        break
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

    // ========== 节点树打印（调试用）==========

    private fun dumpNodeTree(
        node: AccessibilityNodeInfo,
        sb: StringBuilder,
        depth: Int
    ) {
        if (depth > 8) return

        val indent = "  ".repeat(depth)
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val lc = node.isLongClickable
        val rect = Rect().also { node.getBoundsInScreen(it) }

        sb.appendLine("${indent}[${cls}] t='${text}' d='${desc}' id='${viewId}' lc=${lc} ${rect}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNodeTree(child, sb, depth + 1)
            child.recycle()
        }
    }

    // ========== 收集所有可能的文本（多种方式）==========

    private fun collectAllPossibleTexts(
        node: AccessibilityNodeInfo,
        result: MutableList<Pair<String, AccessibilityNodeInfo>>,
        depth: Int
    ) {
        if (depth > 12) return

        // 方式1: text
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            result.add(Pair(text, node))
        }

        // 方式2: contentDescription
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) {
            result.add(Pair(desc, node))
        }

        // 方式3: hintText (API 26+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrEmpty()) {
                result.add(Pair(hint, node))
            }
        }

        // 方式4: tooltipText (API 28+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val tooltip = node.tooltipText?.toString()?.trim()
            if (!tooltip.isNullOrEmpty()) {
                result.add(Pair(tooltip, node))
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllPossibleTexts(child, result, depth + 1)
        }
    }

    // ========== 执行隐藏 ==========

    private suspend fun performHide(targetNode: AccessibilityNodeInfo): Boolean {
        val longClickTarget = findLongClickable(targetNode)

        if (longClickTarget != null) {
            val rect = Rect()
            longClickTarget.getBoundsInScreen(rect)
            log("找到长按节点: ${longClickTarget.className} $rect")

            val ok = longClickTarget.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            log("ACTION_LONG_CLICK: $ok")

            if (ok) {
                delay(1200)
                return clickMenu()
            }

            if (!rect.isEmpty) {
                return gestureAndHide(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        } else {
            val rect = Rect()
            targetNode.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                log("无长按节点，直接手势: $rect")
                return gestureAndHide(rect.centerX().toFloat(), rect.centerY().toFloat())
            }
        }

        return false
    }

    private suspend fun gestureAndHide(x: Float, y: Float): Boolean {
        log("手势长按: ($x, $y)")
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 900L)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
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

        try {
            // 打印菜单所有文本
            val menuDump = StringBuilder()
            dumpNodeTree(root, menuDump, 0)
            log("菜单节点树:\n$menuDump")

            val targets = listOf(
                "不显示该聊天", "不显示", "隐藏对话", "Delete", "Hide"
            )

            for (target in targets) {
                val nodes = root.findAccessibilityNodeInfosByText(target)
                if (nodes.isNullOrEmpty()) continue
                log("找到菜单: '$target'")
                for (node in nodes) {
                    if (clickNodeOrAncestor(node)) {
                        node.recycle()
                        return true
                    }
                    node.recycle()
                }
            }
        } finally {
            root.recycle()
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
        log("未找到菜单，已关闭弹窗")
        return false
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
