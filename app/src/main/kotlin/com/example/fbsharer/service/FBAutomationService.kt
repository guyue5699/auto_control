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
        
        // Step 1: Open Chrome with Facebook Search
        val firstGroup = task.groupNames.split(",")[0].trim()
        val encodedGroup = URLEncoder.encode(firstGroup, "UTF-8")
        val url = "https://m.facebook.com/groups/search/?q=$encodedGroup"
        
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
            
            logBuilder.append("${System.currentTimeMillis()}: 开始处理群组 $groupName\n")
            Log.d(TAG, "Starting post for group: $groupName")
            
            // 1. If not on current group search page, navigate there
            if (groups.indexOf(groupName) > 0) {
                navigateToGroupSearch(groupName)
                delay(3000)
            }

            // 2. Click the group in search results
            if (!clickNodeByText(groupName, false)) {
                logBuilder.append("${System.currentTimeMillis()}: 找不到群组 $groupName\n")
                Log.e(TAG, "Could not find group: $groupName")
                continue
            }
            delay(4000)
            
            // 3. Click "Write something..." or "Start a post"
            val writeSomethingTexts = listOf("Write something...", "Start a post", "写点什么...", "在此输入内容")
            var foundWrite = false
            for (text in writeSomethingTexts) {
                if (clickNodeByText(text, false)) {
                    foundWrite = true
                    break
                }
            }
            
            if (!foundWrite) {
                logBuilder.append("${System.currentTimeMillis()}: 找不到发帖框 $groupName\n")
                Log.e(TAG, "Could not find 'Write something' box")
                continue
            }
            delay(2000)
            
            // 4. Input text and link
            val content = if (task.text.isNotBlank()) "${task.text}\n\n${task.targetUrl}" else task.targetUrl
            val inputHints = listOf("What's on your mind?", "说点什么...", "分享你的想法")
            var foundInput = false
            for (hint in inputHints) {
                if (inputByText(hint, content)) {
                    foundInput = true
                    break
                }
            }
            
            if (!foundInput) {
                inputToFocusedNode(content)
            }
            delay(3000) // Wait for link preview
            
            // 5. Click Post
            val postTexts = listOf("POST", "Post", "发布", "确定")
            var posted = false
            for (text in postTexts) {
                if (clickNodeByText(text, true)) {
                    posted = true
                    break
                }
            }
            
            if (posted) {
                logBuilder.append("${System.currentTimeMillis()}: 群组 $groupName 发布指令已发送\n")
            } else {
                logBuilder.append("${System.currentTimeMillis()}: 群组 $groupName 发布按钮点击失败\n")
            }
            delay(5000)
            
            // 6. Go back to ready for next group search
            performGlobalAction(GLOBAL_ACTION_BACK)
            delay(1000)
            
            // Update logs in DB periodically
            updateTaskLogs(logBuilder.toString())
        }
        
        finishTask(logBuilder.toString())
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
