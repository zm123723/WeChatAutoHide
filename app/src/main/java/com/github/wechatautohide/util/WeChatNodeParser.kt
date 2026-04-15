package com.github.wechatautohide.util

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

object WeChatNodeParser {
    
    private const val TAG = "WeChatNodeParser"
    
    fun findNodesByClassName(
        root: AccessibilityNodeInfo,
        classNames: List<String>
    ): List<AccessibilityNodeInfo> {
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByClassNameRecursive(root, classNames, results)
        return results
    }
    
    private fun findNodesByClassNameRecursive(
        node: AccessibilityNodeInfo,
        classNames: List<String>,
        results: MutableList<AccessibilityNodeInfo>
    ) {
        val className = node.className?.toString() ?: ""
        for (targetClass in classNames) {
            if (className.contains(targetClass)) {
                results.add(node)
                break
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByClassNameRecursive(child, classNames, results)
        }
    }
    
    fun printNodeTree(node: AccessibilityNodeInfo, indent: String = "", maxDepth: Int = 5) {
        if (maxDepth <= 0) return
        
        val className = node.className?.toString() ?: "null"
        val text = node.text?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val clickable = node.isClickable
        val longClickable = node.isLongClickable
        
        Log.d(TAG, "$indent[$className] text='$text' id='$viewId' click=$clickable longClick=$longClickable")
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            printNodeTree(child, "$indent  ", maxDepth - 1)
            child.recycle()
        }
    }
}
