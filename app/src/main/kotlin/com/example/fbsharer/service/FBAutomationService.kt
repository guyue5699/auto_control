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
    private var lastScrollTime = 0L
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.Main)

    // 定时检查器，防止无障碍事件不触发导致“假死”
    private fun startHeartbeat() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            while (isRunning) {
                if (currentTask != null && currentState != State.IDLE && currentState != State.NAVIGATING) {
                    Log.v(TAG, "心跳检查: 当前状态 $currentState")
                    runCurrentStateLogic()
                }
                delay(2000) // 每 2 秒强制检查一次
            }
        }
    }

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
        Log.d(TAG, "✅ 服务已连接")
        android.widget.Toast.makeText(this, "自动化服务已就绪", android.widget.Toast.LENGTH_SHORT).show()
        startHeartbeat()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        isRunning = false
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (currentTask == null || currentState == State.IDLE) return
        
        // 响应窗口变化事件，立即执行逻辑
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            runCurrentStateLogic()
        }
    }

    private fun runCurrentStateLogic() {
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
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "无法获取当前窗口根节点，等待中...")
            return
        }
        
        Log.d(TAG, "开始扫描页面寻找分享按钮...")
        
        // 1. 尝试通过文本识别 (中/英)
        val shareKeywords = listOf("分享", "Share", "转发")
        val shareNodes = mutableListOf<AccessibilityNodeInfo>()
        for (keyword in shareKeywords) {
            shareNodes.addAll(rootNode.findAccessibilityNodeInfosByText(keyword))
        }
        Log.d(TAG, "通过文本找到节点数量: ${shareNodes.size}")

        // 2. 尝试通过 contentDescription (图标模式) 识别
        if (shareNodes.isEmpty()) {
            findNodesByDescription(rootNode, shareKeywords, shareNodes)
            Log.d(TAG, "通过描述找到节点数量: ${shareNodes.size}")
        }

        // 3. 尝试通过结构化特征识别
        if (shareNodes.isEmpty()) {
            findShareByStructure(rootNode, shareNodes)
            Log.d(TAG, "通过结构化找到节点数量: ${shareNodes.size}")
        }

        // 过滤并锁定目标
        val validShareNode = shareNodes.find { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // 极大放宽可见性限制：只要在屏幕范围内（考虑到状态栏和导航栏）
            val screenHeight = resources.displayMetrics.heightPixels
            val isVisible = rect.top > 100 && rect.bottom < screenHeight - 50
            if (!isVisible) Log.v(TAG, "跳过屏幕外节点: $rect (ScreenHeight: $screenHeight)")
            isVisible
        }

        if (validShareNode != null) {
            Log.i(TAG, "🎯 锁定分享按钮，执行物理模拟点击")
            performClick(validShareNode)
            currentState = State.OPENING_SHARE_MENU
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 3000) { // 稍微延长到 3 秒，避免滚动过快
                Log.i(TAG, "未在当前视图发现分享按钮，尝试向下滚动...")
                swipeUp()
                lastScrollTime = currentTime
            }
        }
    }

    private fun findNodesByDescription(root: AccessibilityNodeInfo, keywords: List<String>, result: MutableList<AccessibilityNodeInfo>) {
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val desc = node.contentDescription?.toString() ?: ""
            if (keywords.any { desc.contains(it, ignoreCase = true) }) {
                result.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
    }

    private fun findShareByStructure(root: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            
            // 寻找横向排列的互动条 (通常包含 3 个子按钮)
            if (node.childCount == 3) {
                var clickableCount = 0
                for (i in 0 until node.childCount) {
                    if (node.getChild(i)?.isClickable == true) clickableCount++
                }
                
                if (clickableCount == 3) {
                    // 锁定第三个按钮作为分享按钮
                    node.getChild(2)?.let { result.add(it) }
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
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

        Log.d(TAG, "执行物理模拟滑动: [${width/2}, ${height*0.8}] -> [${width/2}, ${height*0.3}]")

        val path = Path()
        path.moveTo(width / 2f, height * 0.8f)
        path.lineTo(width / 2f, height * 0.3f) // 滑动距离稍微拉长一点

        val gestureBuilder = GestureDescription.Builder()
        // 增加滑动持续时间到 800ms，确保系统能识别为有效滑动
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 800))
        dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "手势滑动完成")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "手势滑动被系统取消，请检查是否有悬浮窗遮挡")
            }
        }, null)
    }

    override fun onInterrupt() {
        instance = null
    }
}
