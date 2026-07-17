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
    
    // 用于记录已经分享过的小组名称，防止重复点击
    private val sharedGroupNames = mutableSetOf<String>()

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
        CLICKING_SHARE_IN_DETAIL,
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
            State.FINDING_POST -> findAndClickPostImageOrShare()
            State.CLICKING_SHARE_IN_DETAIL -> findAndClickShareInDetail()
            State.OPENING_SHARE_MENU -> findAndClickShareToGroup()
            State.SELECTING_GROUP -> findAndClickTargetGroup()
            State.POSTING -> findAndClickFinalPost()
            else -> {}
        }
    }

    fun startAutomation(task: PostTask) {
        currentTask = task
        currentGroupIndex = 0
        sharedGroupNames.clear()
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

    private fun findAndClickPostImageOrShare() {
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
        
        Log.d(TAG, "开始扫描页面寻找帖子图片或分享按钮...")
        
        // 1. 优先寻找帖子里的图片并点击进入详情页
        val imageNodes = mutableListOf<AccessibilityNodeInfo>()
        val dequeForImg = ArrayDeque<AccessibilityNodeInfo>()
        dequeForImg.add(rootNode)
        
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        
        while (dequeForImg.isNotEmpty()) {
            val node = dequeForImg.removeFirst()
            val className = node.className?.toString() ?: ""
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            
            // 图片特征：类名包含 Image，或者高宽都很大（>200），且文字较少，不在屏幕最顶端或最底端
            val isImageClass = className.contains("Image", ignoreCase = true)
            val isLargeMedia = h > 200 && w > screenWidth * 0.5
            val hasLittleText = text.length < 50 && !text.contains("分享") && !text.contains("赞") && !text.contains("评论")
            
            if ((isImageClass || isLargeMedia) && hasLittleText) {
                // 确保它在屏幕有效可视范围内
                if (rect.centerY() > 200 && rect.centerY() < screenHeight - 200) {
                    imageNodes.add(node)
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { dequeForImg.add(it) }
            }
        }
        
        if (imageNodes.isNotEmpty()) {
            // 选一个最靠中间的大图
            val targetImage = imageNodes.maxByOrNull { 
                val r = Rect()
                it.getBoundsInScreen(r)
                Math.abs(r.bottom - r.top) * Math.abs(r.right - r.left) 
            }
            
            if (targetImage != null) {
                Log.i(TAG, "🎯 发现帖子图片，优先点击图片进入详情页...")
                performClick(targetImage)
                
                scope.launch {
                    // 等待页面加载
                    delay(3000)
                    currentState = State.CLICKING_SHARE_IN_DETAIL
                }
                return
            }
        }

        // 2. 如果没有图片，降级使用普通寻找分享按钮逻辑
        Log.d(TAG, "未发现帖子图片，尝试直接寻找分享按钮...")
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
            
            // 只要高度有效（且不过大，防止误点整个图片/帖子容器）且中心点在屏幕内
            val isVisible = height in 10..300 && rect.centerY() > 100 && rect.centerY() < screenHeight - 100
            
            if (!isVisible) Log.v(TAG, "跳过无效、过大或屏幕外节点: $rect (Height: $height)")
            isVisible
        }

        if (validShareNode != null) {
            Log.i(TAG, "🎯 锁定分享按钮，执行物理模拟点击")
            performClick(validShareNode)
            scope.launch {
                delay(1500) // 等待分享菜单动画完全弹出
                currentState = State.OPENING_SHARE_MENU
            }
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

    private fun findAndClickShareInDetail() {
        val rootNode = rootInActiveWindow ?: return
        
        Log.d(TAG, "已进入帖子详情页，开始寻找底部右侧分享按钮...")
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        
        // 1. 优先尝试系统文本查找
        val detailShareKeywords = listOf("分享", "Share", "转发")
        for (kw in detailShareKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(kw)
            val validNode = nodes.find { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val h = Math.abs(rect.bottom - rect.top)
                // 确保它在屏幕下半部分，且尺寸正常
                rect.centerY() > screenHeight * 0.6 && h in 10..300
            }
            if (validNode != null) {
                Log.d(TAG, "🎯 详情页：通过文本精确找到分享按钮，点击展开菜单")
                performClick(validNode)
                scope.launch {
                    delay(1500) // 等待分享菜单动画完全弹出
                    currentState = State.OPENING_SHARE_MENU
                }
                return
            }
        }
        
        // 2. 如果文本查不到（通常是纯图标），使用“空间位置探测法”
        // 收集屏幕最下方（>80%）所有的疑似按钮节点
        val bottomNodes = mutableListOf<AccessibilityNodeInfo>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(rootNode)
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            
            // 只要在屏幕最下方 20% 区域，并且具有一定大小的独立节点
            if (rect.centerY() > screenHeight * 0.8 && h in 20..300 && w in 20..(screenWidth / 2)) {
                // 如果节点可点击，或者是图片/按钮容器
                if (node.isClickable || node.className?.contains("Image") == true || node.className?.contains("Button") == true) {
                    bottomNodes.add(node)
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        
        if (bottomNodes.isNotEmpty()) {
            // 在底部的所有节点中，找到最靠右的那一个（centerX 最大）
            val rightMostNode = bottomNodes.maxByOrNull { 
                val r = Rect()
                it.getBoundsInScreen(r)
                r.centerX() 
            }
            
            if (rightMostNode != null) {
                val r = Rect()
                rightMostNode.getBoundsInScreen(r)
                // 确保它真的在右半边
                if (r.centerX() > screenWidth * 0.6) {
                    Log.d(TAG, "🎯 详情页：通过位置找到最右侧按钮，判定为分享，点击展开菜单")
                    performClick(rightMostNode)
                    scope.launch {
                        delay(1500) // 等待分享菜单动画完全弹出
                        currentState = State.OPENING_SHARE_MENU
                    }
                    return
                }
            }
        }
        
        Log.v(TAG, "详情页中暂未发现分享按钮，等待...")
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
        
        // 增加更严格的关键字匹配，防止因为“分享到”这几个字重合而被截胡
        // 比如如果只搜“分享”，可能点到了“分享到好友的个人主页”
        val exactGroupKeywords = listOf("分享到小组", "Share to a group", "转发到小组")
        
        // 过滤出真正有效的小节点（排除整个 WebView 容器的误判）
        fun isValidTargetNode(node: AccessibilityNodeInfo): Boolean {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            // 真实按钮或列表项的高度通常在 30 ~ 300 之间，过大的是容器，过小的是无效节点
            return h in 30..400 && w > 50
        }

        // 1. 尝试系统 API 按文本精确查找
        for (keyword in exactGroupKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            // 必须确保文字真正包含“小组”或“group”等核心词汇，防止系统 API 返回包含部分词汇的错误节点
            val validNode = nodes.filter { isValidTargetNode(it) }
                                 .filter { 
                                     val text = it.text?.toString() ?: it.contentDescription?.toString() ?: ""
                                     text.contains("小组") || text.contains("group", ignoreCase = true)
                                 }
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
            
            // 必须严格匹配整个词组，而不能只是部分包含
            if (exactGroupKeywords.any { text.contains(it, ignoreCase = true) } && isValidTargetNode(node)) {
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
            Log.d(TAG, "正在全自动遍历小组列表 (已分享过的小组数: ${sharedGroupNames.size})")
            
            // 简单方案：直接找所有包含文字且可点击的节点
            val clickableNodesWithText = findClickableNodesWithText(rootNode)
            
            // 找到当前屏幕上第一个还没分享过的小组
            val unsharedNode = clickableNodesWithText.find { node ->
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                text.isNotBlank() && !sharedGroupNames.contains(text)
            }
            
            if (unsharedNode != null) {
                val text = unsharedNode.text?.toString() ?: unsharedNode.contentDescription?.toString() ?: ""
                Log.d(TAG, "点击尚未分享的小组: $text")
                sharedGroupNames.add(text)
                performClick(unsharedNode)
                currentState = State.POSTING
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime > 2000) {
                    Log.d(TAG, "当前页面的所有可见小组都已分享过，尝试滚动寻找更多")
                    scope.launch {
                        swipeUp()
                        lastScrollTime = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private fun findClickableNodesWithText(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            
            // 识别小组条目的特征：有文字、且不是顶部的搜索框或标题
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (text.isNotBlank() && 
                !text.contains("搜索") && 
                !text.contains("选择小组") && 
                !text.contains("推荐") &&
                !text.contains("群组") &&
                !text.contains("Groups")) {
                
                // 放宽尺寸限制，并结合可点击属性。在 Web 中，列表项本身可能不可点击，但包含文字的子节点可点击
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val h = Math.abs(rect.bottom - rect.top)
                val w = Math.abs(rect.right - rect.left)
                
                val screenWidth = resources.displayMetrics.widthPixels
                
                // 只要高度在合理范围内 (20~400)，并且它是可点击的，或者是占据大部分宽度的容器，就认为它是候选条目
                if (h in 20..400) {
                    if (node.isClickable || w > screenWidth * 0.4) {
                        result.add(node)
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        
        // 去重，防止同一个列表项的不同子节点被多次加入（按照纵坐标进行粗略去重）
        return result.distinctBy { 
            val rect = Rect()
            it.getBoundsInScreen(rect)
            // 如果两个节点的顶部坐标差距在 20 像素以内，就认为是同一行（同一个条目）
            rect.top / 20 
        }
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
        
        // 获取屏幕宽度，右上角的按钮一般在屏幕右半边
        val screenWidth = resources.displayMetrics.widthPixels
        
        // 1. 尝试系统 API 按文本查找
        for (keyword in postKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            val finalPostBtn = nodes.find { node ->
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 右上角特征：位于屏幕上半部分，且偏右
                rect.top < 300 && rect.right > screenWidth * 0.6
            }
            
            if (finalPostBtn != null) {
                Log.d(TAG, "找到最终发布按钮，点击")
                performClick(finalPostBtn)
                
                scope.launch {
                    delay(3000)
                    Log.d(TAG, "发布完成，准备返回主页...")
                    
                    // 模拟全局返回操作，退回到主页
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1000)
                    // 从小组发布返回后，还在详情页，再返回一次退出详情页
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1000)
                    // 退回主页
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1000)
                    
                    currentGroupIndex++
                    // 重复该帖子的下一个小组分享，由于是在详情页，所以重新进入详情页比较麻烦，
                    // 现在的逻辑是回到主页，重新找这个帖子，再点图片进入详情页分享。
                    currentState = State.FINDING_POST 
                    Log.d(TAG, "回到寻找帖子状态，准备分享第 ${currentGroupIndex + 1} 个小组")
                }
                return
            }
        }
        
        // 2. 尝试深度遍历查找 (如果文本查不到)
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(rootNode)
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            
            if (postKeywords.any { text.contains(it, ignoreCase = true) }) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.top < 300 && rect.right > screenWidth * 0.6) {
                    Log.d(TAG, "深度遍历找到最终发布按钮，点击")
                    performClick(node)
                    
                    scope.launch {
                        delay(3000)
                        Log.d(TAG, "发布完成，准备返回主页...")
                        
                        // 模拟全局返回操作，退回到主页
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1000)
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1000)
                        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        delay(1000)
                        
                        currentGroupIndex++
                        currentState = State.FINDING_POST 
                        Log.d(TAG, "回到寻找帖子状态，准备分享第 ${currentGroupIndex + 1} 个小组")
                    }
                    return
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        
        Log.v(TAG, "当前未发现发布按钮，可能仍在加载...")
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
            val path = Path().apply { 
                moveTo(x, y) 
                // 为了兼容某些严格的系统，加入极小的移动使之成为一个合法的触摸事件
                lineTo(x + 1, y + 1)
            }
            // 点击时间增加到 100ms，让系统确实认为这是一次“点击”而不是意外触碰
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
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

        Log.i(TAG, "正在执行强制滚动手势 (缓慢模式)...")

        val path = Path()
        // 把滑动范围大幅度缩小，完全集中在屏幕正中间
        // 从 60% 滑到 40%，远离任何屏幕边缘
        path.moveTo(width / 2f, height * 0.6f)
        path.lineTo(width / 2f, height * 0.4f)

        val gestureBuilder = GestureDescription.Builder()
        // 把滑动时间从 600 毫秒拉长到 1500 毫秒，变成“慢慢拖动”而不是“快速猛划(Fling)”
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, 1500))
        
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
