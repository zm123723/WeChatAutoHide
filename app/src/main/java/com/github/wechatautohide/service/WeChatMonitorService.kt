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
        if (event.packageName != WECHAT_PACKAGE) return
        if (isProcessing) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastCheckTime = now
                log("窗口变化: ${event.className}")
                serviceScope.launch { scanAndHide() }
            }
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
            if (contacts.isEmpty()) {
                log("名单为空")
                return
            }

            val hideNames = contacts
                .filter { it.enabled }
                .map { it.name.trim() }
                .toSet()

            log("名单: $hideNames")

            val rootNode = rootInActiveWindow ?: run {
                log("无法获取窗口")
                return
            }

            for (name in hideNames) {
                val now = System.currentTimeMillis()
                if ((now - (processedMap[name] ?: 0)) < PROCESS_COOLDOWN) continue

                val success = tryHide(rootNode, name, contacts)
                if (success) processedMap[name] = now
            }

            rootNode.recycle()

        } catch (e: Exception) {
            log("出错: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    private suspend fun tryHide(
        root: AccessibilityNodeInfo,
        name: String,
        contacts: List<HideContact>
    ): Boolean {
        // 方法1：findAccessibilityNodeInfosByText
        val nodes = root.findAccessibilityNodeInfosByText(name)
        log("搜索'$name'找到${nodes?.size ?: 0}个节点")

        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                val t = node.text?.toString()?.trim() ?: ""
                val d = node.contentDescription?.toString()?.trim() ?: ""
                log("  节点: text='$t' desc='$d' class=${node.className} longClick=${node.isLongClickable}")

                if (t == name || d == name) {
                    val target = getLongClickTarget(node)
                    if (target != null) {
                        log("  执行长按")
                        val ok = longClickAndHide(target)
                        target.recycle()
                        nodes.forEach { it.recycle() }
                        if (ok) {
                            contacts.find { it.name == name }?.let {
                                database.hideContactDao().incrementHideCount(it.id)
                            }
                            log("✅ 隐藏成功: $name")
                        }
                        return ok
                    } else {
                        log("  未找到可长按父节点")
                    }
                }
                node.recycle()
            }
            nodes.forEach { it.recycle() }
        }

        // 方法2：遍历整个节点树
        log("方法1失败，遍历节点树")
        return traverseTree(root, name, contacts)
    }

    private suspend fun traverseTree(
        node: AccessibilityNodeInfo,
        name: String,
        contacts: List<HideContact>
    ): Boolean {
        val t = node.text?.toString()?.trim() ?: ""
        val d = node.contentDescription?.toString()?.trim() ?: ""

        if (t == name || d == name) {
            log("遍历找到: '$t'")
            val target = getLongClickTarget(node)
            if (target != null) {
                val ok = longClickAndHide(target)
                target.recycle()
                if (ok) {
                    contacts.find { it.name == name }?.let {
                        database.hideContactDao().incrementHideCount(it.id)
                    }
                    log("✅ 遍历隐藏成功: $name")
                }
                return ok
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = traverseTree(child, name, contacts)
            child.recycle()
            if (found) return true
        }
        return false
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
        if (rect.isEmpty) {
            log("边界为空")
            return false
        }

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
        log("未找到菜单，已关闭")
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        log("服务已停止")
    }
}
