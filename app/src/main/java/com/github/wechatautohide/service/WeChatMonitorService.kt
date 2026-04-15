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
    
    private fun scanChatList() {
        if (isProcessing) {
            Log.d(TAG, "正在处理中，跳过")
            return
        }
        
        isProcessing = true
        
        serviceScope.launch {
            try {
                val rootNode = rootInActiveWindow
                if (rootNode == null) {
                    Log.w(TAG, "无法获取根节点")
                    isProcessing = false
                    return@launch
                }
                
                Log.d(TAG, "开始扫描聊天列表")
                
                val chatItems = findAllChatItems(rootNode)
                Log.d(TAG, "找到 ${chatItems.size} 个聊天项")
                
                val hideContacts = database.hideContactDao().getAllContactsSync()
                val hideNames = hideContacts.filter { it.enabled }.map { it.name }.toSet()
                
                Log.d(TAG, "需要隐藏的联系人: $hideNames")
                
                for (chatItem in chatItems) {
                    val contactName = extractContactNameFromNode(chatItem)
                    
                    if (contactName.isNotEmpty() && hideNames.contains(contactName)) {
                        Log.d(TAG, "发现目标联系人: $contactName")
                        
                        if (!processedContacts.contains(contactName)) {
                            hideChatItem(chatItem, contactName)
                            processedContacts.add(contactName)
                            
                            val contact = hideContacts.find { it.name == contactName }
                            contact?.let {
                                database.hideContactDao().incrementHideCount(it.id)
                            }
                            
                            delay(500)
                        }
                    }
                    
                    chatItem.recycle()
                }
                
                rootNode.recycle()
                
            } catch (e: Exception) {
                Log.e(TAG, "扫描聊天列表失败", e)
            } finally {
                isProcessing = false
            }
        }
    }
    
    private fun findAllChatItems(rootNode: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val chatItems = mutableListOf<AccessibilityNodeInfo>()
        
        val listViews = WeChatNodeParser.findNodesByClassName(
            rootNode, 
            listOf("ListView", "RecyclerView")
        )
        
        for (listView in listViews) {
            for (i in 0 until listView.childCount) {
                val child = listView.getChild(i)
                if (child != null && isValidChatItem(child)) {
                    chatItems.add(child)
                }
            }
        }
        
        if (chatItems.isEmpty()) {
            chatItems.addAll(findClickableContainers(rootNode))
        }
        
        return chatItems
    }
    
    private fun isValidChatItem(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable && !node.isLongClickable) {
            return false
        }
        
        val hasText = hasTextContent(node)
        if (!hasText) {
            return false
        }
        
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val height = rect.height()
        
        return height in 100..600
    }
    
    private fun hasTextContent(node: AccessibilityNodeInfo): Boolean {
        if (!node.text.isNullOrEmpty()) {
            return true
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasTextContent(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        
        return false
    }
    
    private fun findClickableContainers(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val containers = mutableListOf<AccessibilityNodeInfo>()
        
        if (isValidChatItem(node)) {
            containers.add(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            containers.addAll(findClickableContainers(child))
        }
        
        return containers
    }
