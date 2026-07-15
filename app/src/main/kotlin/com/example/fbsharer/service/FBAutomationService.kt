package com.example.fbsharer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.fbsharer.data.PostTask
import com.example.fbsharer.data.TaskStatus
import kotlinx.coroutines.*
import android.net.Uri
import java.net.URLEncoder

class FBAutomationService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var currentTask: PostTask? = null
    private var step = 0
    private var isRunning = false

    companion object {
        private const val TAG = "FBAutomationService"
        var instance: FBAutomationService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service Connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning || currentTask == null) return

        // We can monitor window changes to decide next steps
        val rootNode = rootInActiveWindow ?: return
        
        // Log.d(TAG, "Event: ${event.eventType}, Package: ${event.packageName}")
    }

    override fun onInterrupt() {
        isRunning = false
    }

    fun startAutomation(task: PostTask) {
        currentTask = task
        isRunning = true
        step = 0
        
        // Step 1: Open Chrome with the Post URL
        val url = task.targetUrl
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.setPackage("com.android.chrome")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
            serviceScope.launch {
                executeWorkflow()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chrome not found or error: ${e.message}")
            isRunning = false
        }
    }

    private suspend fun executeWorkflow() {
        val task = currentTask ?: return
        val groups = task.groupNames.split(",").map { it.trim() }
        val logBuilder = StringBuilder()

        for (groupName in groups) {
            if (!isRunning) break
            
            logBuilder.append("${System.currentTimeMillis()}: 开始处理分享至群组 $groupName\n")
            Log.d(TAG, "Starting share to group: $groupName")
            
            // 1. Ensure we are on the post page (re-navigate for each group to reset state)
            if (groups.indexOf(groupName) > 0) {
                navigateToUrl(task.targetUrl)
                delay(4000)
            } else {
                delay(5000) // Initial load wait
            }

            // 2. Find and click "Share" button
            val shareTexts = listOf("Share", "分享", "发送")
            var foundShare = false
            for (text in shareTexts) {
                if (clickNodeByText(text, false)) {
                    foundShare = true
                    break
                }
            }
            
            if (!foundShare) {
                logBuilder.append("${System.currentTimeMillis()}: 找不到分享按钮\n")
                continue
            }
            delay(2000)

            // 3. Find and click "Share to a group"
            val shareToGroupTexts = listOf("Share to a group", "分享到小组", "在小组中分享")
            var foundGroupOption = false
            for (text in shareToGroupTexts) {
                if (clickNodeByText(text, false)) {
                    foundGroupOption = true
                    break
                }
            }
            
            if (!foundGroupOption) {
                logBuilder.append("${System.currentTimeMillis()}: 找不到‘分享到小组’选项\n")
                continue
            }
            delay(3000)
            
            // 4. Search for the group name
            val searchHints = listOf("Search for groups", "搜索小组", "查找")
            var searched = false
            for (hint in searchHints) {
                if (inputByText(hint, groupName)) {
                    searched = true
                    break
                }
            }
            
            if (!searched) {
                // Try focusing and typing if hint not found
                inputToFocusedNode(groupName)
            }
            delay(3000)

            // 5. Click the group from search results
            if (!clickNodeByText(groupName, false)) {
                logBuilder.append("${System.currentTimeMillis()}: 搜索结果中找不到群组 $groupName\n")
                continue
            }
            delay(3000)
            
            // 6. Click Post
            val postTexts = listOf("POST", "Post", "发布", "确定", "分享")
            var posted = false
            for (text in postTexts) {
                if (clickNodeByText(text, true)) {
                    posted = true
                    break
                }
            }
            
            if (posted) {
                logBuilder.append("${System.currentTimeMillis()}: 群组 $groupName 分享指令已发送\n")
            } else {
                logBuilder.append("${System.currentTimeMillis()}: 群组 $groupName 发布按钮点击失败\n")
            }
            delay(5000)
            
            // Update logs in DB
            updateTaskLogs(logBuilder.toString())
        }
        
        finishTask(logBuilder.toString())
    }

    private fun navigateToUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.setPackage("com.android.chrome")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun updateTaskLogs(logs: String) {
        currentTask?.let {
            serviceScope.launch(Dispatchers.IO) {
                com.example.fbsharer.data.AppDatabase.getDatabase(this@FBAutomationService)
                    .postTaskDao()
                    .updateLogs(it.id, logs)
            }
        }
    }

    private fun finishTask(finalLogs: String) {
        isRunning = false
        currentTask?.let {
            serviceScope.launch(Dispatchers.IO) {
                val dao = com.example.fbsharer.data.AppDatabase.getDatabase(this@FBAutomationService)
                    .postTaskDao()
                dao.updateLogs(it.id, finalLogs)
                dao.updateStatus(it.id, TaskStatus.COMPLETED)
            }
        }
    }

    private fun navigateToGroupSearch(groupName: String) {
        val encodedGroup = URLEncoder.encode(groupName, "UTF-8")
        val url = "https://m.facebook.com/groups/search/?q=$encodedGroup"
        navigateToUrl(url)
    }

    private fun inputToFocusedNode(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            return true
        }
        return false
    }

    private fun clickNodeByText(text: String, exact: Boolean): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = if (exact) {
            rootNode.findAccessibilityNodeInfosByText(text)
        } else {
            // Partial match logic
            rootNode.findAccessibilityNodeInfosByText(text)
        }
        
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }

    private fun inputByText(hint: String, text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val nodes = rootNode.findAccessibilityNodeInfosByText(hint)
        for (node in nodes) {
            if (node.isEditable) {
                val arguments = Bundle()
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                return true
            }
        }
        return false
    }
}
