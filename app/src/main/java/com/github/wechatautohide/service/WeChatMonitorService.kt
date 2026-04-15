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
    private val CHECK_INTERVAL = 2000L
    private val processedMap = mutableMapOf<String, Long>()
    private val PROCESS_COOLDOWN = 30000L

    // 微信消息列表联系人名称的ViewId（不同版本可能不同）
    private val WECHAT_CONTACT_NAME_IDS = listOf(
        "com.tencent.mm:id/b4z",
        "com.tencent.mm:id/b51",
        "com.tencent.mm:id/nk_",
        "com.tencent.mm:id/contact_name",
        "com.tencent.mm:id/nickname",
        "com.tencent.mm:id/name",
        "com.tencent.mm:id/b4x",
        "com.tencent.mm:id/b4y",
        "com.tencent.mm:id/b50",
        "com.tencent.mm:id/b52",
        "com.tencent.mm:id/b53"
    )

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
    
    // 打印当前包名，帮助调试
    val pkg = event.packageName?.toString() ?: ""
    if (pkg == WECHAT_PACKAGE) {
        log("📱 微信事件: ${event.eventType}")
    }
    
    if (pkg != WECHAT_PACKAGE) return
        if (isProcessing) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastCheckTime = now
                serviceScope.launch { scanAndHide() }
            }
        }
    }

    private suspend fun scanAndHide() {
        if (isProcessing) return
        isProcessing = true

        try {
            val contacts = database.hideContactDao().getAllContactsSync()
                .filter { it.enabled }
            if (contacts.isEmpty()) return

            val hideNames = contacts.map { it.name.trim() }.toSet()

            val rootNode = rootInActiveWindow ?: return

            // 第一步：先扫描所有节点，打印ViewId找规律
            dumpAllNodes(rootNode, hideNames)

            // 第二步：用ViewId查找
            var found = false
            for (id in WECHAT_CONTACT_NAME_IDS) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    log("ViewId '$id' 找到 ${nodes.size} 个节点")
                    for (node in nodes) {
                        val text = node.text?.toString()?.trim() ?: ""
                        val desc = node.contentDescription?.toString()?.trim() ?: ""
                        log("  ViewId节点: text='$text' desc='$desc'")

                        for (name in hideNames) {
                            if (text == name || desc == name) {
                                log("✅ ViewId方式找到: $name")
                                val target = getLongClickTarget(node)
                                if (target != null) {
                                    val ok = longClickAndHide(target)
                                    if (ok) {
                                        processedMap[name] = System.currentTimeMillis()
                                        contacts.find { it.name == name }?.let {
                                            database.hideContactDao().incrementHideCount(it.id)
                                        }
                                        log("✅ 隐藏成功: $name")
                                        found = true
                                    }
                                    target.recycle()
                                }
                                break
                            }
                        }
                        node.recycle()
                    }
                }
            }

            rootNode.recycle()

        } catch (e: Exception) {
            log("出错: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    // 扫描并打印所有节点的ViewId和文本（帮助找规律）
    private fun dumpAllNodes(
        node: AccessibilityNodeInfo,
        hideNames: Set<String>,
        depth: Int = 0
    ) {
        if (depth > 8) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""

        // 只打印有ViewId的节点
        if (viewId.isNotEmpty()) {
            val isTarget = hideNames.any { 
                text.contains(it) || desc.contains(it) 
            }
            if (isTarget) {
                log("🎯 ViewId='$viewId' text='$text' desc='$desc' class=$className")
            } else if (text.isNotEmpty() || desc.isNotEmpty()) {
                log("📋 ViewId='$viewId' text='$text' class=$className")
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpAllNodes(child, hideNames, depth + 1)
            child.recycle()
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
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)

        delay(1200)
        return clickMenu()
    }

    private fun clickMenu(): Boolean {
        val root = rootInActiveWindow ?: return false

        val options = listOf("不显示该聊天", "不显示", "Delete", "隐藏对话")
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
