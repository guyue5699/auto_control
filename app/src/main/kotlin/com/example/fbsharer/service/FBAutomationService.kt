package com.example.fbsharer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.fbsharer.data.PostTask
import com.example.fbsharer.data.TaskStatus
import android.widget.Toast
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
        serviceScope.launch {
            Toast.makeText(this@FBAutomationService, "自动化群控服务已就绪", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!isRunning || currentTask == null) return

        // We can monitor window changes to decide next steps
        val rootNode = rootInActiveWindow ?: return
    }

    override fun onInterrupt() {
        isRunning = false
    }

    fun startAutomation(task: PostTask) {
        currentTask = task
        isRunning = true
        step = 0
        
        Toast.makeText(this, "正在跳转至您的个人主页...", Toast.LENGTH_LONG).show()
        Log.d(TAG, "Starting automation for task: ${task.id}")

        serviceScope.launch {
            try {
                // 第一步：先进入 Facebook 触屏版首页，确保登录态
                navigateToUrl("https://m.facebook.com/")
                delay(3000)
                
                // 第二步：通过特定的 profile 链接进入，这在移动端最稳定
                navigateToUrl("https://m.facebook.com/profile.php")
                delay(6000)
                
                executeWorkflow()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting workflow: ${e.message}")
                Toast.makeText(this@FBAutomationService, "启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                isRunning = false
            }
        }
    }

    private suspend fun executeWorkflow() {
        val task = currentTask ?: return
        val logBuilder = StringBuilder()

        logBuilder.append("${System.currentTimeMillis()}: 开始全自动分享任务\n")
        
        // 确保已经在主页，如果还在广场，尝试点击头像进入
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            val profileTexts = listOf("个人主页", "Profile", "我的", "Me")
            for (text in profileTexts) {
                if (clickNodeByText(text, false)) {
                    showToast("检测到还在广场，尝试点击进入个人主页...")
                    delay(5000)
                    break
                }
            }
        }

        var postIndex = 0
        val maxPosts = 5 // 限制处理最近的5个帖子，避免账号异常
        
        while (postIndex < maxPosts && isRunning) {
            logBuilder.append("${System.currentTimeMillis()}: 准备处理第 ${postIndex + 1} 个帖子\n")
            showToast("正在寻找第 ${postIndex + 1} 个帖子的分享按钮...")
            
            // 重新获取当前页面的分享按钮列表
            val rootNode = rootInActiveWindow ?: break
            
            // 深度扫描逻辑：
            // 1. 扫描所有文字为“分享”或“Share”的节点
            // 2. 扫描所有 contentDescription 包含“分享”或“Share”的节点
            // 3. 扫描所有 class 为 Button 或 Image 的可点击节点（作为兜底）
            val shareNodes = mutableListOf<AccessibilityNodeInfo>()
            val potentialTexts = listOf("分享", "Share", "发送", "Send")
            
            fun collectShareNodes(node: AccessibilityNodeInfo) {
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                
                if (potentialTexts.any { text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) }) {
                    if (node.isClickable || node.parent?.isClickable == true || node.parent?.parent?.isClickable == true) {
                        shareNodes.add(node)
                    }
                }
                
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { collectShareNodes(it) }
                }
            }
            
            collectShareNodes(rootNode)
            
            // 去重（基于节点在屏幕上的位置）
            val uniqueShareNodes = shareNodes.distinctBy { 
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                "${rect.left},${rect.top}"
            }

            if (uniqueShareNodes.isEmpty()) {
                logBuilder.append("${System.currentTimeMillis()}: 没看到分享按钮，尝试滚动页面...\n")
                showToast("未发现帖子，尝试向下滑动...")
                
                val scrollableNode = findScrollableNode(rootNode)
                if (scrollableNode != null) {
                    scrollableNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } else {
                    // 如果找不到滚动容器，尝试直接发送滑动位移（需要 Android 7.0+）
                    dispatchScroll()
                }
                delay(4000)
                continue
            }

            if (postIndex >= uniqueShareNodes.size) {
                logBuilder.append("${System.currentTimeMillis()}: 当前视图内的帖子已处理完，滚动加载更多...\n")
                findScrollableNode(rootNode)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                delay(3000)
                postIndex = 0 // 重置索引以重新扫描新加载的帖子
                continue
            }

            val currentShareButton = uniqueShareNodes[postIndex]
            showToast("找到帖子，正在点击分享...")
            
            // 尝试多级点击
            var clicked = false
            if (currentShareButton.isClickable) {
                clicked = currentShareButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) {
                clicked = currentShareButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (!clicked) {
                clicked = currentShareButton.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }

            if (!clicked) {
                showToast("点击分享按钮失败")
                postIndex++
                continue
            }
            delay(3000)

            // 点击“分享到小组”
            val shareToGroupTexts = listOf("Share to a group", "分享到小组", "在小组中分享", "Group")
            var foundGroupOption = false
            for (text in shareToGroupTexts) {
                if (clickNodeByText(text, false)) {
                    foundGroupOption = true
                    break
                }
            }
            
            if (foundGroupOption) {
                showToast("已进入群组选择列表")
                delay(5000) // 等待群组列表加载
                
                // --- 自动化遍历群组列表 ---
                var groupIndex = 0
                val maxGroupsPerPost = 10 
                
                while (groupIndex < maxGroupsPerPost && isRunning) {
                    val groupListRoot = rootInActiveWindow ?: break
                    val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
                    findClickableNodes(groupListRoot, clickableNodes)
                    
                    // 过滤掉头部的搜索框和返回按钮等干扰项
                    val targetGroups = clickableNodes.filter { 
                        val txt = it.text?.toString() ?: it.contentDescription?.toString() ?: ""
                        txt != "搜索小组" && txt != "Search" && txt != "返回" && txt.isNotBlank() &&
                        !shareToGroupTexts.contains(txt) && !shareTexts.contains(txt)
                    }

                    if (groupIndex >= targetGroups.size) {
                        logBuilder.append("${System.currentTimeMillis()}: 该帖子已处理完列表可见群组\n")
                        break
                    }

                    val groupNode = targetGroups[groupIndex]
                    val groupNameText = groupNode.text?.toString() ?: groupNode.contentDescription?.toString() ?: "未知群组"
                    
                    showToast("分享至: $groupNameText")
                    logBuilder.append("${System.currentTimeMillis()}: 正在分享至 -> $groupNameText\n")
                    
                    // 点击选中的群组
                    groupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(3000)
                    
                    // 点击最终的“发布/分享”按钮
                    val postTexts = listOf("POST", "Post", "发布", "确定", "分享", "Send")
                    var posted = false
                    for (text in postTexts) {
                        if (clickNodeByText(text, true)) {
                            posted = true
                            break
                        }
                    }
                    
                    if (posted) {
                        showToast("成功分享至 $groupNameText")
                        logBuilder.append("${System.currentTimeMillis()}: 成功分享至 $groupNameText\n")
                        delay(6000) 
                        break 
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2000)
                    }
                    groupIndex++
                }
            } else {
                showToast("未能找到‘分享到小组’菜单")
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(2000)
            }

            // 重新进入个人主页刷新状态，处理下一个帖子
            navigateToUrl("https://m.facebook.com/me")
            delay(5000)
            postIndex++
            
            updateTaskLogs(logBuilder.toString())
        }
        
        finishTask(logBuilder.toString())
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(this@FBAutomationService, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dispatchScroll() {
        val path = android.graphics.Path()
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.2f)
        val builder = android.accessibilityservice.GestureDescription.Builder()
        builder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 100, 500))
        dispatchGesture(builder.build(), null, null)
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = findScrollableNode(child)
                if (found != null) return found
            }
        }
        return null
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
