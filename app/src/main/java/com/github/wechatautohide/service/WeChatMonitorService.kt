package com.github.wechatautohide.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.github.wechatautohide.database.AppDatabase
import com.github.wechatautohide.util.WeChatNodeParser
import kotlinx.coroutines.*

class WeChatMonitorService : AccessibilityService() {
    
    private val TAG = "WeChatMonitor"
    private val WECHAT_PACKAGE = "com.tencent.mm"
    private val LAUNCHER_UI = "com.tencent.mm.ui.LauncherUI"
    
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var isProcessing = false
    private var lastCheckTime = 0L
    private val CHECK_INTERVAL = 1000L
    
    private val processedContacts = mutableSetOf<String>()
    
    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(this)
        Log.d(TAG, "监控服务已创建")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        
        if (event.packageName != WECHAT_PACKAGE) return
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleContentChange(event)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChange(event)
            }
        }
    }
    
    private fun handleWindowStateChange(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: return
        
        Log.d(TAG, "窗口变化: $className")
        
        if (className == LAUNCHER_UI) {
            serviceScope.launch {
                delay(500)
                scanChatList()
            }
        }
    }
    
    private fun handleContentChange(event: AccessibilityEvent) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastCheckTime < CHECK_INTERVAL) {
            return
        }
        
        val rootNode = rootInActiveWindow ?: return
        val packageName = rootNode.packageName?.toString()
        
        if (packageName == WECHAT_PACKAGE) {
            if (isChatListView(event)) {
                Log.d(TAG, "检测到聊天列表变化")
                lastCheckTime = currentTime
                scanChatList()
            }
        }
        
        rootNode.recycle()
    }
    
    private fun isChatListView(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString() ?: return false
        return className.contains("ListView") || 
               className.contains("RecyclerView")
    }
