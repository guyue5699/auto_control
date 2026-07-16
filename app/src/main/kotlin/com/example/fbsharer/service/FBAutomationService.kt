package com.example.fbsharer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.fbsharer.data.PostTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FBAutomationService : AccessibilityService() {

    companion object {
        private const val TAG = "FBAutomationService"
        var instance: FBAutomationService? = null
    }

    private var currentTask: PostTask? = null
    private var currentState = State.IDLE
    private var currentGroupIndex = 0
    private val scope = CoroutineScope(Dispatchers.Main)

    enum class State {
        IDLE,
        NAVIGATING,
        FINDING_POST,
        OPENING_SHARE_MENU,
        SELECTING_SHARE_TO_GROUP,
        SELECTING_GROUP,
        POSTING
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "服务已连接")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (currentTask == null || currentState == State.IDLE) return

        // 核心自动化逻辑
        when (currentState) {
            State.FINDING_POST -> findAndClickShareButton()
            State.OPENING_SHARE_MENU -> findAndClickShareToGroup()
            State.SELECTING_GROUP -> findAndClickTargetGroup()
            State.POSTING -> findAndClickFinalPost()
            else -> {}
        }
    }

    fun startAutomation(task: PostTask) {
        currentTask = task
        currentGroupIndex = 0
        currentState = State.NAVIGATING
        Log.d(TAG, "开始任务: ${task.targetUrl}")
        
        // 启动浏览器逻辑
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(task.targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            // 优先尝试使用 Chrome
            intent.setPackage("com.android.chrome")
            startActivity(intent)
            Log.d(TAG, "成功启动 Chrome")
        } catch (e: Exception) {
            Log.e(TAG, "未找到 Chrome，尝试使用默认浏览器: ${e.message}")
            try {
                intent.setPackage(null) // 清除包名，使用系统默认分发
                startActivity(intent)
                Log.d(TAG, "成功启动默认浏览器")
            } catch (e2: Exception) {
                Log.e(TAG, "无可用的浏览器: ${e2.message}")
                // 可以在这里发一个广播或回调通知 UI 弹出 Toast
            }
        }

        scope.launch {
            delay(5000) // 等待浏览器启动和页面加载
            currentState = State.FINDING_POST
        }
    }

    private fun findAndClickShareButton() {
        val rootNode = rootInActiveWindow ?: return
        val shareKeywords = listOf("分享", "Share")
        
        val shareNodes = mutableListOf<AccessibilityNodeInfo>()
        for (keyword in shareKeywords) {
            shareNodes.addAll(rootNode.findAccessibilityNodeInfosByText(keyword))
        }

        // 过滤掉顶部的“分享新鲜事”
        val validShareNode = shareNodes.find { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top > 200 // 避开顶部区域
        }

        if (validShareNode != null) {
            Log.d(TAG, "找到分享按钮，准备点击")
            performClick(validShareNode)
            currentState = State.OPENING_SHARE_MENU
        } else {
            // 尝试滚动
            Log.d(TAG, "未找到分享按钮，尝试向下滚动")
            swipeUp()
        }
    }

    private fun findAndClickShareToGroup() {
        val rootNode = rootInActiveWindow ?: return
        val groupKeywords = listOf("分享到小组", "Share to a group", "转发到小组")
        
        for (keyword in groupKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "找到‘分享到小组’，点击")
                performClick(nodes[0])
                currentState = State.SELECTING_GROUP
                return
            }
        }
    }

    private fun findAndClickTargetGroup() {
        val rootNode = rootInActiveWindow ?: return
        
        // 获取所有疑似小组列表的容器或条目
        // FB 移动版小组列表通常是简单的列表项
        val allNodes = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isClickable && (node.className?.contains("ViewGroup") == true || node.className?.contains("LinearLayout") == true)) {
                // 进一步检查是否包含小组特征（如文本）
                val text = node.text?.toString() ?: ""
                if (text.isNotEmpty() && !text.contains("搜索") && !text.contains("小组")) {
                    allNodes.add(node)
                }
            }
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }
        
        // 如果指定了群组名，则按名称找
        val specifiedGroups = currentTask?.groupNames?.split(",")?.filter { it.isNotBlank() }?.map { it.trim() }
        if (!specifiedGroups.isNullOrEmpty()) {
            if (currentGroupIndex >= specifiedGroups.size) {
                Log.d(TAG, "指定小组已分享完毕")
                finishCurrentPost()
                return
            }
            val targetGroupName = specifiedGroups[currentGroupIndex]
            val nodes = rootNode.findAccessibilityNodeInfosByText(targetGroupName)
            if (nodes.isNotEmpty()) {
                Log.d(TAG, "找到目标小组: $targetGroupName，点击")
                performClick(nodes[0])
                currentState = State.POSTING
            } else {
                Log.d(TAG, "未找到小组: $targetGroupName，尝试滚动")
                swipeUp()
            }
        } else {
            // “全自动遍历”模式
            Log.d(TAG, "正在全自动遍历小组列表 (当前索引: $currentGroupIndex)")
            
            // 简单方案：直接找所有包含文字且可点击的节点
            val clickableNodesWithText = findClickableNodesWithText(rootNode)
            
            if (currentGroupIndex >= clickableNodesWithText.size) {
                Log.d(TAG, "当前页面的小组已遍历完，尝试滚动寻找更多")
                swipeUp()
                // 如果滚动后依然没有新节点，则视为结束
                return
            }

            val targetNode = clickableNodesWithText[currentGroupIndex]
            Log.d(TAG, "点击第 ${currentGroupIndex + 1} 个小组: ${targetNode.text}")
            performClick(targetNode)
            currentState = State.POSTING
        }
    }

    private fun findClickableNodesWithText(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            
            // 识别小组条目的特征：可点击、有文字、且不是顶部的搜索框或标题
            val text = node.text?.toString() ?: ""
            if (node.isClickable && text.isNotBlank() && 
                !text.contains("搜索") && !text.contains("小组") && !text.contains("推荐")) {
                result.add(node)
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        return result
    }

    private fun finishCurrentPost() {
        Log.d(TAG, "当前帖子分享任务结束")
        currentGroupIndex = 0
        currentState = State.FINDING_POST
        // 自动向下滚动寻找下一个帖子
        swipeUp()
    }

    private fun findAndClickFinalPost() {
        val rootNode = rootInActiveWindow ?: return
        val postKeywords = listOf("发布", "Post", "分享", "Share")
        
        // 通常在右上角
        for (keyword in postKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            val finalPostBtn = nodes.find { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                rect.top < 300 && rect.right > 500 // 右上角特征
            }
            
            if (finalPostBtn != null) {
                Log.d(TAG, "找到最终发布按钮，点击")
                performClick(finalPostBtn)
                
                scope.launch {
                    delay(3000)
                    currentGroupIndex++
                    // 重复该帖子的下一个小组分享
                    currentState = State.FINDING_POST 
                }
                return
            }
        }
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        var target = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val path = Path()
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.2f)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 500))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    override fun onInterrupt() {
        instance = null
    }
}
