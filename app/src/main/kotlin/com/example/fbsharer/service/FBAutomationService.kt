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
        val logBuilder = StringBuilder()

        logBuilder.append("${System.currentTimeMillis()}: 开始全自动分享任务（所有帖子 -> 所有小组）\n")
        
        // 1. 进入个人主页
        navigateToUrl("https://m.facebook.com/me")
        delay(6000)

        var postIndex = 0
        val maxPosts = 5 // 限制处理最近的5个帖子，避免账号异常
        
        while (postIndex < maxPosts && isRunning) {
            logBuilder.append("${System.currentTimeMillis()}: 准备处理第 ${postIndex + 1} 个帖子\n")
            
            // 重新获取当前页面的分享按钮列表
            val rootNode = rootInActiveWindow ?: break
            val shareNodes = rootNode.findAccessibilityNodeInfosByText("分享")
                .filter { it.isClickable || it.parent?.isClickable == true }
            
            if (postIndex >= shareNodes.size) {
                logBuilder.append("${System.currentTimeMillis()}: 当前页面没有更多帖子了\n")
                break
            }

            // --- 开始分享单个帖子的流程 ---
            val currentShareButton = shareNodes[postIndex]
            
            // 点击分享按钮
            if (currentShareButton.isClickable) {
                currentShareButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                currentShareButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            delay(2000)

            // 点击“分享到小组”
            val shareToGroupTexts = listOf("Share to a group", "分享到小组", "在小组中分享")
            var foundGroupOption = false
            for (text in shareToGroupTexts) {
                if (clickNodeByText(text, false)) {
                    foundGroupOption = true
                    break
                }
            }
            
            if (foundGroupOption) {
                delay(4000) // 等待群组列表加载
                
                // --- 自动化遍历群组列表 ---
                var groupIndex = 0
                val maxGroupsPerPost = 20 // 每个帖子最多分享到20个群组，防封号
                
                while (groupIndex < maxGroupsPerPost && isRunning) {
                    val groupListRoot = rootInActiveWindow ?: break
                    // 寻找列表中的群组项。在 FB 移动网页版中，群组项通常是可点击的布局
                    // 我们通过查找页面上非搜索框、非标题的可点击元素来模拟
                    val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
                    findClickableNodes(groupListRoot, clickableNodes)
                    
                    // 过滤掉头部的搜索框和返回按钮等干扰项
                    // 这里的逻辑需要根据实际 UI 进一步微调，目前采取按顺序点击策略
                    val targetGroups = clickableNodes.filter { 
                        val txt = it.text?.toString() ?: it.contentDescription?.toString() ?: ""
                        // 排除一些已知的干扰词
                        txt != "搜索小组" && txt != "Search" && txt != "返回" && txt.isNotBlank()
                    }

                    if (groupIndex >= targetGroups.size) {
                        logBuilder.append("${System.currentTimeMillis()}: 帖子 ${postIndex + 1} 已处理完列表中的所有群组\n")
                        break
                    }

                    val groupNode = targetGroups[groupIndex]
                    val groupNameText = groupNode.text?.toString() ?: "未知群组"
                    
                    logBuilder.append("${System.currentTimeMillis()}: 帖子 ${postIndex + 1} 正在分享至 -> $groupNameText\n")
                    
                    // 点击选中的群组
                    groupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(3000)
                    
                    // 点击最终的“发布/分享”按钮
                    val postTexts = listOf("POST", "Post", "发布", "确定", "分享")
                    var posted = false
                    for (text in postTexts) {
                        if (clickNodeByText(text, true)) {
                            posted = true
                            break
                        }
                    }
                    
                    if (posted) {
                        logBuilder.append("${System.currentTimeMillis()}: 成功分享至 $groupNameText\n")
                        delay(5000) // 关键：群组间分享必须有较长延迟，防止封号
                        
                        // 分享成功后，FB 通常会关闭选择列表。我们需要重新点击“分享”按钮进入下一轮
                        // 所以这里需要跳出群组循环，重新点击该帖子的分享按钮
                        break 
                    } else {
                        // 如果没发布成功，尝试返回上一级
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2000)
                    }
                    groupIndex++
                }
            }

            // 重新进入个人主页刷新状态，处理下一个帖子
            navigateToUrl("https://m.facebook.com/me")
            delay(5000)
            postIndex++
            
            updateTaskLogs(logBuilder.toString())
        }
        
        finishTask(logBuilder.toString())
    }

    private fun findClickableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && node.isVisibleToUser) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findClickableNodes(child, list)
            }
        }
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
