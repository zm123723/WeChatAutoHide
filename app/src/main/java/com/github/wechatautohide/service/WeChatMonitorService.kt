package com.github.wechatautohide.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    // 已处理过的联系人（防止重复操作）
    // key=联系人名, value=上次处理时间
    private val processedMap = mutableMapOf<String, Long>()
    private val PROCESS_COOLDOWN = 30000L // 30秒内不重复处理同一个人

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        Log.d(TAG, "✅ 无障碍服务已启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != WECHAT_PACKAGE) return
        if (isProcessing) return

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < CHECK_INTERVAL) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastCheckTime = now
                serviceScope.launch {
                    scanAndHide()
                }
            }
        }
    }

    private suspend fun scanAndHide() {
        if (isProcessing) return
        isProcessing = true

        try {
            // 获取数据库中的隐藏名单
            val contacts = database.hideContactDao().getAllContactsSync()
            if (contacts.isEmpty()) {
                Log.d(TAG, "隐藏名单为空")
                return
            }

            val hideNames = contacts
                .filter { it.enabled }
                .map { it.name.trim() }
                .toSet()

            Log.d(TAG, "隐藏名单: $hideNames")

            val rootNode = rootInActiveWindow ?: run {
                Log.d(TAG, "无法获取根节点")
                return
            }

            // 打印当前界面信息（调试用）
            val windowClass = rootNode.className?.toString() ?: ""
            Log.d(TAG, "当前窗口: $windowClass")

            // 对每个需要隐藏的联系人进行处理
            for (name in hideNames) {
                val now = System.currentTimeMillis()
                val lastProcess = processedMap[name] ?: 0
                if (now - lastProcess < PROCESS_COOLDOWN) {
                    Log.d(TAG, "跳过 $name（冷却中）")
                    continue
                }

                val found = findContactInList(rootNode, name)
                if (found) {
                    Log.d(TAG, "找到联系人: $name，开始隐藏")
                    processedMap[name] = now

                    val contact = contacts.find { it.name == name }
                    contact?.let {
                        database.hideContactDao().incrementHideCount(it.id)
                    }
                }
            }

            rootNode.recycle()

        } catch (e: Exception) {
            Log.e(TAG, "扫描出错: ${e.message}")
        } finally {
            isProcessing = false
        }
    }

    private suspend fun findContactInList(
        root: AccessibilityNodeInfo,
        targetName: String
    ): Boolean {
        // 方法1：直接搜索文本
        val nodes = root.findAccessibilityNodeInfosByText(targetName)
        if (nodes.isNullOrEmpty()) {
            Log.d(TAG, "未找到文本节点: $targetName")
            return false
        }

        Log.d(TAG, "找到 ${nodes.size} 个包含 '$targetName' 的节点")

        for (node in nodes) {
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            Log.d(TAG, "节点文本: '$text' 描述: '$desc' 类: ${node.className}")

            // 检查是否完全匹配
            if (text == targetName || desc == targetName) {
                // 找到目标，向上找可长按的父节点
                val longClickTarget = findLongClickableAncestor(node)
                if (longClickTarget != null) {
                    Log.d(TAG, "找到可长按节点，执行长按")
                    val success = doLongClickAndHide(longClickTarget)
                    longClickTarget.recycle()
                    node.recycle()
                    nodes.forEach { it.recycle() }
                    return success
                }
            }
            node.recycle()
        }

        nodes.forEach { it.recycle() }
        return false
    }

    private fun findLongClickableAncestor(
        node: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        // 先检查节点本身
        if (node.isLongClickable) return node

        // 向上查找最多10层
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 10) {
            if (parent.isLongClickable) {
                return parent
            }
            val grandParent = parent.parent
            if (depth > 0) parent.recycle()
            parent = grandParent
            depth++
        }
        return null
    }

    private suspend fun doLongClickAndHide(node: AccessibilityNodeInfo): Boolean {
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            if (rect.isEmpty) {
                Log.d(TAG, "节点边界为空")
                return false
            }

            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()

            Log.d(TAG, "长按坐标: ($x, $y)")

            // 执行长按手势
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 800)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)

            // 等待菜单弹出
            delay(1200)

            // 点击隐藏选项
            val result = clickHideMenuItem()
            Log.d(TAG, "点击隐藏菜单: $result")
            result

        } catch (e: Exception) {
            Log.e(TAG, "长按出错: ${e.message}")
            false
        }
    }

    private fun clickHideMenuItem(): Boolean {
        val root = rootInActiveWindow ?: return false

        // 微信"不显示该聊天"的可能文本
        val menuTexts = listOf(
            "不显示该聊天",
            "不显示",
            "Delete",
            "隐藏对话"
        )

        for (menuText in menuTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(menuText)
            if (nodes.isNullOrEmpty()) continue

            Log.d(TAG, "找到菜单项: $menuText")

            for (node in nodes) {
                // 尝试点击节点本身
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    node.recycle()
                    root.recycle()
                    return true
                }

                // 尝试点击父节点
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 5) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        parent.recycle()
                        node.recycle()
                        root.recycle()
                        return true
                    }
                    val gp = parent.parent
                    parent.recycle()
                    parent = gp
                    depth++
                }
                node.recycle()
            }
        }

        root.recycle()

        // 没找到菜单，按返回键关闭
        Log.d(TAG, "未找到隐藏菜单，按返回键")
        performGlobalAction(GLOBAL_ACTION_BACK)
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "服务销毁")
    }
}
