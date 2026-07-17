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
        lastScrollTime = 0L
        Log.d(TAG, "🚀 开始启动流程...")

        val baseIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(task.targetUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pm = packageManager
        val preferredPackages = listOf(
            "com.android.chrome",
            "com.google.android.apps.chrome"
        )
        val discoveredBrowserPackages = pm.queryIntentActivities(baseIntent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .filterNot { it == "com.google.android.googlequicksearchbox" }
            .distinct()
        val targetPkg = (preferredPackages + discoveredBrowserPackages).firstOrNull { packageName ->
            Intent(baseIntent).setPackage(packageName).resolveActivity(pm) != null
        }
        val launchIntent = if (targetPkg != null) {
            Log.i(TAG, "✅ 找到可用浏览器包名: $targetPkg")
            Intent(baseIntent).setPackage(targetPkg)
        } else {
            Log.w(TAG, "⚠️ 未锁定到 Chrome，将尝试系统默认浏览器")
            baseIntent
        }

        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动浏览器失败: ${e.message}")
        }

        scope.launch {
            delay(5000)
            currentState = State.FINDING_POST
            Log.d(TAG, "✅ 已进入帖子扫描阶段，立即开始寻找分享按钮")
            runCurrentStateLogic()
        }
    }

    private fun findAndClickShareButton() {
        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            Log.w(TAG, "无法获取当前窗口根节点，尝试主动滑动触发页面刷新")
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 2000) {
                scope.launch {
                    swipeUp()
                    lastScrollTime = System.currentTimeMillis()
                }
            }
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
            
            // 修复 Rect 异常逻辑：如果 bottom < top，说明坐标系可能有误，取绝对高度
            val height = if (rect.bottom > rect.top) rect.bottom - rect.top else rect.top - rect.bottom
            val screenHeight = resources.displayMetrics.heightPixels
            
            // 过滤掉文字是 Reels / 全部 / 照片 / 签到 / 生活纪事 的误判节点
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (text.contains("Reels", ignoreCase = true) || 
                text.contains("照片") || 
                text.contains("全部") ||
                text.contains("签到") ||
                text.contains("生活纪") ||
                text.contains("Photos", ignoreCase = true) ||
                text.contains("All", ignoreCase = true) ||
                text.contains("Check in", ignoreCase = true) ||
                text.contains("Life event", ignoreCase = true)) {
                return@find false
            }
            
            // 只要高度有效且中心点在屏幕内
            val isVisible = height > 0 && rect.centerY() > 100 && rect.centerY() < screenHeight - 100
            
            if (!isVisible) Log.v(TAG, "跳过无效或屏幕外节点: $rect (Height: $height)")
            isVisible
        }

        if (validShareNode != null) {
            Log.i(TAG, "🎯 锁定分享按钮，执行物理模拟点击")
            performClick(validShareNode)
            currentState = State.OPENING_SHARE_MENU
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 2000) {
                Log.i(TAG, "未在当前视图发现分享按钮，强制触发滑动...")
                // 确保在主线程执行手势
                scope.launch {
                    swipeUp()
                    lastScrollTime = System.currentTimeMillis()
                }
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
                var hasTabKeywords = false
                
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child?.isClickable == true) clickableCount++
                    
                    // 检查是否是主页的 Tab 栏（全部 / 照片 / Reels / 签到 / 生活纪事）
                    val text = child?.text?.toString() ?: child?.contentDescription?.toString() ?: ""
                    if (text.contains("Reels", ignoreCase = true) || 
                        text.contains("照片") || 
                        text.contains("全部") ||
                        text.contains("签到") ||
                        text.contains("生活纪") ||
                        text.contains("Photos", ignoreCase = true) ||
                        text.contains("All", ignoreCase = true) ||
                        text.contains("Check in", ignoreCase = true) ||
                        text.contains("Life event", ignoreCase = true)) {
                        hasTabKeywords = true
                    }
                }
                
                // 必须是 3 个按钮，且不能包含 Tab 栏的特征文字
                if (clickableCount == 3 && !hasTabKeywords) {
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
        
        // 过滤出真正有效的小节点（排除整个 WebView 容器的误判）
        fun isValidTargetNode(node: AccessibilityNodeInfo): Boolean {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            // 真实按钮或列表项的高度通常在 30 ~ 300 之间，过大的是容器，过小的是无效节点
            return h in 30..400 && w > 50
        }

        // 1. 尝试系统 API 按文本查找
        for (keyword in groupKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            // 倒序查找，或者按面积最小的找，确保找到的是最深层的子节点（真正的按钮）
            val validNode = nodes.filter { isValidTargetNode(it) }
                                 .minByOrNull { Math.abs(it.boundsInScreen.bottom - it.boundsInScreen.top) }
            
            if (validNode != null) {
                Log.d(TAG, "通过系统文本查找找到‘分享到小组’ (真实节点)，点击")
                performClick(validNode)
                currentState = State.SELECTING_GROUP
                return
            }
        }

        // 2. 尝试深度遍历查找 (处理 contentDescription 或嵌套结构)
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(rootNode)
        
        var bestNode: AccessibilityNodeInfo? = null
        var minHeight = Int.MAX_VALUE
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            
            if (groupKeywords.any { text.contains(it, ignoreCase = true) } && isValidTargetNode(node)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val h = Math.abs(rect.bottom - rect.top)
                // 记录高度最小的匹配节点（最具体的叶子节点）
                if (h < minHeight) {
                    minHeight = h
                    bestNode = node
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        
        if (bestNode != null) {
            Log.d(TAG, "通过深度遍历找到‘分享到小组’ (真实节点)，点击")
            performClick(bestNode)
            currentState = State.SELECTING_GROUP
            return
        }
        
        Log.v(TAG, "当前屏幕未发现‘分享到小组’按钮，等待弹出...")
    }

    // 扩展属性用于获取 Rect (兼容旧代码)
    private val AccessibilityNodeInfo.boundsInScreen: Rect
        get() {
            val r = Rect()
            getBoundsInScreen(r)
            return r
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
        val rect = Rect()
        node.getBoundsInScreen(rect)
        
        // 修复 Rect 异常：Webview 中有时 bottom 和 top 颠倒
        val top = Math.min(rect.top, rect.bottom)
        val bottom = Math.max(rect.top, rect.bottom)
        val left = Math.min(rect.left, rect.right)
        val right = Math.max(rect.left, rect.right)
        
        val x = left + (right - left) / 2f
        val y = top + (bottom - top) / 2f

        if (x > 0 && y > 0) {
            Log.d(TAG, "执行物理坐标点击: ($x, $y) - Text: ${node.text}")
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            
            val result = dispatchGesture(gesture, null, null)
            if (!result) {
                Log.w(TAG, "物理坐标点击失败，降级使用节点 ACTION_CLICK")
                fallbackNodeClick(node)
            }
        } else {
            fallbackNodeClick(node)
        }
    }

    private fun fallbackNodeClick(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun swipeUp() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        Log.i(TAG, "正在执行强制滚动手势...")

        val path = Path()
        // 从屏幕最底部开始滑，避开中间可能存在的浮窗或固定容器
        path.moveTo(width / 2f, height * 0.9f)
        path.lineTo(width / 2f, height * 0.1f)

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 600))
        
        val result = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "手势下发完成")
                if (currentState == State.FINDING_POST) {
                    scope.launch {
                        delay(1200)
                        Log.d(TAG, "滚动完成，重新扫描分享按钮")
                        runCurrentStateLogic()
                    }
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.e(TAG, "手势被系统拦截，请检查是否有覆盖层")
            }
        }, null)

        if (!result) {
            Log.e(TAG, "手势启动失败，切换系统底层滚动指令...")
            // Fallback: 尝试对当前根节点执行前向滚动动作
            val scrollPerformed = rootInActiveWindow?.performAction(
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ) == true
            Log.d(TAG, "节点前向滚动结果: $scrollPerformed")
            if (scrollPerformed && currentState == State.FINDING_POST) {
                scope.launch {
                    delay(1200)
                    Log.d(TAG, "节点滚动完成，重新扫描分享按钮")
                    runCurrentStateLogic()
                }
            }
        }
    }

    override fun onInterrupt() {
        instance = null
    }
}
