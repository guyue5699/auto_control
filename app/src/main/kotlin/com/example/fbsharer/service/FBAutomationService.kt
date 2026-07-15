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
    private var automationJob: Job? = null
    private var currentTask: PostTask? = null
    private var isRunning = false

    companion object {
        private const val TAG = "FBAutomationService"
        var instance: FBAutomationService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Service Connected")
        showToast("自动化群控服务已就绪")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        automationJob?.cancel()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {
        isRunning = false
        automationJob?.cancel()
    }

    fun startAutomation(task: PostTask) {
        automationJob?.cancel()
        currentTask = task
        isRunning = true
        
        automationJob = serviceScope.launch {
            try {
                showToast("正在通过 Intent 唤起分享流程...")
                
                // 方案升级：不再通过浏览器打开，而是直接发送分享 Intent
                // 这会强制系统弹出分享对话框，绕过浏览器内复杂的 UI 识别
                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = "text/plain"
                shareIntent.putExtra(Intent.EXTRA_TEXT, "https://m.facebook.com/profile.php") // 这里的 URL 可以是主页或特定帖子
                shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                // 尝试直接定向到 Facebook 应用或 Chrome 的分享组件
                val chooser = Intent.createChooser(shareIntent, "选择分享方式")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chooser)
                
                delay(5000)
                executeWorkflow()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting workflow: ${e.message}")
                showToast("启动失败: ${e.message}")
                isRunning = false
            }
        }
    }

    private suspend fun executeWorkflow() {
        val task = currentTask ?: return
        val logBuilder = StringBuilder()

        logBuilder.append("${System.currentTimeMillis()}: 开始全自动分享任务\n")
        
        var postIndex = 0
        val maxPosts = 20 // 增加处理上限
        val potentialTexts = listOf("分享", "Share", "转发", "发送", "Send")
        val shareToGroupTexts = listOf("Share to a group", "分享到小组", "在小组中分享", "Group")
        val postTexts = listOf("POST", "Post", "发布", "确定", "分享", "Send")
        
        while (postIndex < maxPosts && isRunning) {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                delay(2000)
                continue
            }

            // 检查是否还在广场（如果由于 FB 重定向跳到了首页）
            val profileTexts = listOf("个人主页", "Profile", "我的", "Me")
            var onProfilePage = false
            // 这里简单判断，如果看到“你在想什么”或者特定的个人主页标识，就认为在主页
            // 实际上 profile.php 已经很稳了，我们重点放在找按钮上
            
            showToast("正在寻找第 ${postIndex + 1} 个帖子的分享按钮...")
            
            // --- 核心升级：基于布局结构的精准识别 ---
            // 1. 寻找帖子容器 (Post Container)
            val postContainers = mutableListOf<AccessibilityNodeInfo>()
            fun findContainers(node: AccessibilityNodeInfo) {
                // Facebook 帖子的容器通常具有特定的特征：包含多个子节点且高度较大
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                val metrics = resources.displayMetrics
                
                // 排除顶部工具栏
                if (rect.top < metrics.heightPixels * 0.15) return
                
                // 识别特征：高度在屏幕 20% 到 80% 之间，且子节点较多
                if (rect.height() > metrics.heightPixels * 0.2 && node.childCount > 5) {
                    postContainers.add(node)
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { findContainers(it) }
                }
            }
            findContainers(rootNode)

            // 2. 在容器内寻找动作条 (Action Bar) 并锁定分享按钮
            val shareNodes = mutableListOf<AccessibilityNodeInfo>()
            for (container in postContainers) {
                // 在帖子容器底部区域寻找按钮
                val containerRect = android.graphics.Rect()
                container.getBoundsInScreen(containerRect)
                
                fun findShareInContainer(node: AccessibilityNodeInfo) {
                    val nodeRect = android.graphics.Rect()
                    node.getBoundsInScreen(nodeRect)
                    
                    // 特征：位于容器底部 30% 区域，且位于右侧 40% 区域
                    val isAtBottom = nodeRect.top > containerRect.top + containerRect.height() * 0.6
                    val isAtRight = nodeRect.left > containerRect.left + containerRect.width() * 0.5
                    
                    if (isAtBottom && isAtRight && (node.isClickable || node.parent?.isClickable == true)) {
                        // 进一步检查是否包含分享相关的关键词或图标特征
                        val text = node.text?.toString() ?: ""
                        val desc = node.contentDescription?.toString() ?: ""
                        if (potentialTexts.any { text.contains(it, ignoreCase = true) || desc.contains(it, ignoreCase = true) } || 
                            node.className.contains("Button") || node.className.contains("Image")) {
                            shareNodes.add(node)
                        }
                    }
                    
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { findShareInContainer(it) }
                    }
                }
                findShareInContainer(container)
            }
            
            val uniqueShareNodes = shareNodes.distinctBy { 
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                "${rect.left},${rect.top}"
            }.sortedBy { 
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                rect.top 
            }

            if (uniqueShareNodes.isEmpty() || postIndex >= uniqueShareNodes.size) {
                showToast("当前位置未发现更多帖子，正在向下滚动...")
                dispatchScroll()
                delay(4000)
                postIndex = 0 // 滚动后重新从新屏幕的第一个开始找
                continue
            }

            val currentShareButton = uniqueShareNodes[postIndex]
            showToast("找到帖子，准备分享...")
            
            var clicked = false
            if (currentShareButton.isClickable) {
                clicked = currentShareButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (!clicked) clicked = currentShareButton.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!clicked) clicked = currentShareButton.parent?.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

            if (!clicked) {
                postIndex++
                continue
            }
            delay(3000)

            // 点击“分享到小组”
            var foundGroupOption = false
            for (text in shareToGroupTexts) {
                if (clickNodeByText(text, false)) {
                    foundGroupOption = true
                    break
                }
            }
            
            if (foundGroupOption) {
                showToast("进入群组列表")
                delay(4000)
                
                var groupIndex = 0
                while (groupIndex < 10 && isRunning) {
                    val groupListRoot = rootInActiveWindow ?: break
                    val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
                    findClickableNodes(groupListRoot, clickableNodes)
                    
                    val targetGroups = clickableNodes.filter { 
                        val txt = it.text?.toString() ?: it.contentDescription?.toString() ?: ""
                        txt.isNotBlank() && !shareToGroupTexts.contains(txt) && !potentialTexts.contains(txt) &&
                        txt != "搜索小组" && txt != "Search" && txt != "返回"
                    }

                    if (groupIndex >= targetGroups.size) break

                    val groupNode = targetGroups[groupIndex]
                    val groupName = groupNode.text?.toString() ?: groupNode.contentDescription?.toString() ?: "未知群组"
                    
                    showToast("转发至: $groupName")
                    groupNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(3000)
                    
                    var posted = false
                    for (text in postTexts) {
                        if (clickNodeByText(text, true)) {
                            posted = true
                            break
                        }
                    }
                    
                    if (posted) {
                        showToast("分享成功")
                        delay(5000)
                        break // 分享成功后 FB 会关闭弹窗，跳出群组循环
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        delay(2000)
                    }
                    groupIndex++
                }
            } else {
                // 如果没找到分享到小组，可能点错或者是其他菜单，点返回
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(2000)
            }
            
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
