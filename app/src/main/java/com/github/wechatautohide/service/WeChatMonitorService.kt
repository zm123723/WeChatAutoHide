package com.github.wechatautohide.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.github.wechatautohide.database.AppDatabase
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
    private val processedContacts = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        Log.d(TAG, "服务已启动")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != WECHAT_PACKAGE) return

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCheckTime < CHECK_INTERVAL) return
        if (isProcessing) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastCheckTime = currentTime
                scanChatList()
            }
        }
    }

    private fun scanChatList() {
        isProcessing = true
        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow ?: return@launch
                val hideContacts = database.hideContactDao().getAllContactsSync()
                val hideNames = hideContacts
                    .filter { it.enabled }
                    .map { it.name.trim() }
                    .toSet()

                if (hideNames.isEmpty()) {
                    rootNode.recycle()
                    return@launch
                }

                Log.d(TAG, "扫描中，隐藏名单: $hideNames")
                findAndHideContacts(rootNode, hideNames, hideContacts)
                rootNode.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "扫描失败: ${e.message}")
            } finally {
                isProcessing = false
            }
        }
    }

    private suspend fun findAndHideContacts(
        rootNode: AccessibilityNodeInfo,
        hideNames: Set<String>,
        hideContacts: List<com.github.wechatautohide.database.HideContact>
    ) {
        for (name in hideNames) {
            if (processedContacts.contains(name)) continue

            // 直接搜索包含该名称的节点
            val nodes = rootNode.findAccessibilityNodeInfosByText(name)
            if (nodes.isNullOrEmpty()) continue

            for (node in nodes) {
                val nodeText = node.text?.toString()?.trim() ?: ""

                // 确保是完全匹配而不是部分匹配
                if (nodeText == name) {
                    Log.d(TAG, "找到目标联系人: $name")

                    // 找到可长按的父节点
                    val clickableParent = findLongClickableParent(node)
                    if (clickableParent != null) {
                        val success = performLongClickAndHide(clickableParent, name)
                        if (success) {
                            processedContacts.add(name)
                            val contact = hideContacts.find { it.name == name }
                            contact?.let {
                                database.hideContactDao().incrementHideCount(it.id)
                            }
                        }
                        clickableParent.recycle()
                    }
                    break
                }
                node.recycle()
            }
        }
    }

    private fun findLongClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node.parent
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isLongClickable) {
                return current
            }
            val parent = current.parent
            current.recycle()
            current = parent
            depth++
        }
        return null
    }

    private suspend fun performLongClickAndHide(
        node: AccessibilityNodeInfo,
        contactName: String
    ): Boolean {
        return try {
            // 执行长按
            val rect = Rect()
            node.getBoundsInScreen(rect)

            val path = Path()
            path.moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())

            val stroke = GestureDescription.StrokeDescription(path, 0, 800)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)

            // 等待菜单出现
            delay(1000)

            // 点击"不显示该聊天"
            val result = clickHideOption()
            Log.d(TAG, "隐藏 $contactName ${if (result) "成功" else "失败"}")
            result

        } catch (e: Exception) {
            Log.e(TAG, "长按失败: ${e.message}")
            false
        }
    }

    private fun clickHideOption(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val texts = listOf("不显示该聊天", "不显示", "Delete", "隐藏")
        for (text in texts) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(text)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        node.recycle()
                        rootNode.recycle()
                        return true
                    }
                    // 找父节点点击
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            parent.recycle()
                            node.recycle()
                            rootNode.recycle()
                            return true
                        }
                        val temp = parent.parent
                        parent.recycle()
                        parent = temp
                    }
                    node.recycle()
                }
            }
        }

        rootNode.recycle()
        // 没找到按钮，按返回键关闭菜单
        performGlobalAction(GLOBAL_ACTION_BACK)
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "服务中断")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
