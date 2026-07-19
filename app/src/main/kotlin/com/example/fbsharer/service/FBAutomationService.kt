package com.example.fbsharer.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.fbsharer.data.PostTask
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
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
    private var stateFailCount = 0
    private var targetBrowserPackage = "com.android.chrome"
    private val scope = CoroutineScope(Dispatchers.Main)
    
    // 用于记录已经分享过的小组名称，防止重复点击
    private val sharedGroupNames = mutableSetOf<String>()

    // 定时检查器，防止无障碍事件不触发导致“假死”
    private fun startHeartbeat() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            while (isRunning) {
                if (currentTask != null && currentState != State.IDLE && currentState != State.NAVIGATING && currentState != State.WAITING) {
                    val currentTime = System.currentTimeMillis()
                    // 仅当距上次滚动超过 3 秒时，才触发心跳检测（防止滚动期间被心跳打断或并发触发）
                    if (currentTime - lastScrollTime > 3000) {
                        Log.v(TAG, "心跳检查: 当前状态 $currentState")
                        runCurrentStateLogic()
                    }
                }
                delay(2000) // 每 2 秒强制检查一次
            }
        }
    }

    enum class State {
        IDLE,
        WAITING,
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
        if (currentTask == null || currentState == State.IDLE || currentState == State.WAITING) return
        
        // 【核心安全锁】检查当前是否还在目标浏览器里
        val currentPackage = event.packageName?.toString() ?: ""
        
        // 允许系统UI（如下拉状态栏）、系统弹窗（android）和我们自己的App（启动过渡期），不做拦截
        if (currentPackage == "com.android.systemui" || currentPackage == "android" || currentPackage == packageName) {
            return
        }
        
        if (currentPackage.isNotEmpty() && currentPackage != targetBrowserPackage) {
            Log.e(TAG, "🚨 严重警告：当前已脱离浏览器进入包 [$currentPackage]！立即挂起所有操作！")
            // 自动拉起浏览器，尝试恢复现场
            bringBrowserToFront()
            return
        }
        
        // 响应窗口变化事件，立即执行逻辑
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || 
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            // 全局拦截：处理系统弹窗（例如浏览器达到标签页上限）
            val root = rootInActiveWindow
            if (root != null) {
                val dialogNodes = root.findAccessibilityNodeInfosByText("已达到标签页数上限")
                if (dialogNodes.isNotEmpty()) {
                    Log.w(TAG, "🚨 检测到浏览器系统弹窗：已达到标签页数上限，尝试自动关闭")
                    val confirmNodes = root.findAccessibilityNodeInfosByText("确定")
                    if (confirmNodes.isNotEmpty()) {
                        performClick(confirmNodes[0])
                        return
                    }
                }
            }
            
            runCurrentStateLogic()
        }
    }
    
    private fun bringBrowserToFront() {
        if (currentState == State.WAITING) return
        currentState = State.WAITING
        Log.i(TAG, "尝试重新唤起浏览器 ($targetBrowserPackage) 回到前台...")
        scope.launch {
            try {
                // 不再重新发送带 URL 的 Intent (避免刷新和新建标签页)，只唤起该应用的启动 Activity
                val intent = packageManager.getLaunchIntentForPackage(targetBrowserPackage)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    startActivity(intent)
                    delay(2000)
                    Log.i(TAG, "已尝试恢复浏览器，退回到寻找帖子状态重试")
                    currentState = State.FINDING_POST
                } else {
                    Log.e(TAG, "无法找到浏览器包: $targetBrowserPackage，停止任务")
                    currentState = State.IDLE
                }
            } catch (e: Exception) {
                Log.e(TAG, "恢复浏览器失败: ${e.message}")
            }
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
        stateFailCount = 0
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
            .filterNot { it == "com.google.android.googlequicksearchbox" || it.contains("com.facebook.katana") || it.contains("com.facebook.lite") || it.contains("com.facebook.orca") }
            .distinct()
        val targetPkg = (preferredPackages + discoveredBrowserPackages).firstOrNull { packageName ->
            Intent(baseIntent).setPackage(packageName).resolveActivity(pm) != null
        }
        
        targetBrowserPackage = targetPkg ?: "com.android.chrome"
        
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
            if (currentTime - lastScrollTime > 3000) {
                lastScrollTime = currentTime
                scope.launch {
                    swipeUp()
                }
            }
            return
        }
        
        // 如果当前是第一个要分享的小组，先尝试跳过主页的干扰区域
        if (currentGroupIndex == 0 && sharedGroupNames.isEmpty()) {
            val isFirstTime = lastScrollTime == 0L
            if (isFirstTime) {
                Log.d(TAG, "首次进入主页，执行初始化滚动以跳过封面区域...")
                lastScrollTime = System.currentTimeMillis()
                scope.launch {
                    delay(2000) // 等待页面加载
                    swipeUp()
                }
                return
            }
        }
        
        Log.d(TAG, "开始扫描页面寻找分享按钮...")
        
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        val shareKeywords = listOf("分享", "Share", "转发")
        var exactShareNode: AccessibilityNodeInfo? = null
        
        // 我们直接使用你在详情页找纯图标那套逻辑！
        // 收集屏幕上半部分以下（>25%）所有的疑似按钮节点
        val candidateNodes = mutableListOf<AccessibilityNodeInfo>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        if (rootNode != null) {
            deque.add(rootNode)
        }
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            
            // 必须是在 Chrome 浏览器内的节点，排除掉我们自己的悬浮窗或系统UI
            val isChromeNode = node.packageName?.toString()?.contains("chrome") == true
            
            // 只要在屏幕 25% 以下区域，并且具有一定大小的独立节点
            if (isChromeNode && rect.centerY() > screenHeight * 0.25 && h in 20..200 && w in 20..(screenWidth / 2)) {
                if (node.isClickable || node.className?.contains("Image") == true || node.className?.contains("Button") == true) {
                    candidateNodes.add(node)
                }
            }
            
            // 顺便找找有没有原生的带“分享”标签的按钮（防止误点包含分享二字的长文本帖子）
            val contentDesc = node.contentDescription?.toString() ?: ""
            val textStr = node.text?.toString() ?: ""
            val hasShareKeyword = shareKeywords.any { 
                (contentDesc.contains(it, ignoreCase = true) && contentDesc.length < 15) || 
                (textStr.contains(it, ignoreCase = true) && textStr.length < 15) 
            }
            
            if (isChromeNode && hasShareKeyword) {
                // 确保它不是一个巨大的容器节点
                if (h in 20..200 && w in 20..(screenWidth / 2)) {
                    Log.d(TAG, "🔍 发现带文字标签的疑似分享按钮: text=[$textStr] desc=[$contentDesc] rect=[${rect.toShortString()}]")
                    exactShareNode = node
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }

        // 1. 优先使用带标签的节点
        var validShareNode = exactShareNode

        // 2. 如果没有带标签的，启动“结构分析法”（找一排按钮的倒数第二个）
        if (validShareNode == null && candidateNodes.isNotEmpty()) {
            // 按照 Y 坐标分组，找到同一水平线上的按钮排
            val rows = candidateNodes.groupBy { 
                val r = Rect()
                it.getBoundsInScreen(r)
                r.centerY() / 80 // 每80像素算作同一行，增加容错度
            }.values.filter { it.size >= 2 } // 只要有2个以上的同行按钮（比如赞、分享）就算数
            
            if (rows.isNotEmpty()) {
                Log.d(TAG, "🔍 结构分析：屏幕上发现 ${rows.size} 排候选按钮")
                rows.forEachIndexed { index, row ->
                    val r = Rect()
                    row[0].getBoundsInScreen(r)
                    Log.d(TAG, "  👉 第 $index 排 (Y=${r.centerY()}): 包含 ${row.size} 个按钮")
                    row.forEach { n ->
                        val nr = Rect()
                        n.getBoundsInScreen(nr)
                        Log.v(TAG, "      - 按钮: [${nr.left},${nr.top}-${nr.right},${nr.bottom}] text=${n.text} desc=${n.contentDescription}")
                    }
                }

                // 【关键修复】：取屏幕中“最靠上”的一排按钮
                // 必须过滤掉主页顶部的发帖工具栏（如：照片、签到、生活纪事）
                val targetRow = rows.filter { row ->
                    val r = Rect()
                    row[0].getBoundsInScreen(r)
                    
                    // 检查这一排按钮中是否包含“发帖”相关的干扰词
                    val hasCreatePostWords = row.any { node ->
                        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                        text.contains("照片") || text.contains("签到") || text.contains("生活") || text.contains("Photo")
                    }
                    
                    if (hasCreatePostWords) {
                        Log.d(TAG, "  🚫 过滤掉发帖工具栏: Y=${r.centerY()}")
                    }
                    if (r.centerY() >= screenHeight - 150) {
                        Log.d(TAG, "  🚫 过滤掉底部系统栏/广告: Y=${r.centerY()}")
                    }
                    
                    r.centerY() < screenHeight - 150 && !hasCreatePostWords
                }.minByOrNull { row ->
                    val r = Rect()
                    row[0].getBoundsInScreen(r)
                    r.centerY()
                }
                
                if (targetRow != null) {
                    val r = Rect()
                    targetRow[0].getBoundsInScreen(r)
                    Log.d(TAG, "✅ 最终选中目标排: Y=${r.centerY()}")
                    
                    // 从左到右排序
                    val sortedRow = targetRow.sortedBy { 
                        val nr = Rect()
                        it.getBoundsInScreen(nr)
                        nr.left 
                    }
                    
                    // 过滤掉最右侧可能是“三个点”或“更多”的按钮
                    val filteredRow = sortedRow.filter { node ->
                        val desc = node.contentDescription?.toString() ?: ""
                        val textStr = node.text?.toString() ?: ""
                        !desc.contains("更多") && !desc.contains("More", true) && !textStr.contains("更多")
                    }
                    
                    if (filteredRow.isNotEmpty()) {
                        // 取过滤后的最右侧一个（必定是分享箭头）
                        validShareNode = filteredRow.last()
                        Log.d(TAG, "🎯 主页：通过结构位置找到分享图标")
                    }
                } else {
                    Log.d(TAG, "❌ 过滤后没有符合条件的按钮排")
                }
            } else {
                Log.d(TAG, "❌ 没有找到任何包含2个以上按钮的排")
            }
        }

        if (validShareNode != null) {
            val rect = Rect()
            validShareNode.getBoundsInScreen(rect)
            val textStr = validShareNode.text?.toString() ?: validShareNode.contentDescription?.toString() ?: "纯图标"
            Log.i(TAG, "🎯 锁定分享按钮，准备点击! 坐标: [${rect.centerX()}, ${rect.centerY()}] 识别文本: [$textStr]")
            
            stateFailCount = 0
            currentState = State.WAITING
            
            performClick(validShareNode)
            
            scope.launch {
                delay(1500) // 等待分享菜单动画完全弹出
                currentState = State.OPENING_SHARE_MENU
                runCurrentStateLogic()
            }
        } else {
            stateFailCount++
            Log.v(TAG, "当前屏幕未发现分享按钮，向下滚动...")
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastScrollTime > 2000) {
                lastScrollTime = currentTime
                scope.launch {
                    swipeUp() // 使用快速大跳滚动
                }
            }
            // 如果连续8次都找不到，为了防死循环，强制重置状态
            if (stateFailCount > 8) {
                Log.w(TAG, "连续多次滚动仍未找到分享按钮，重置计数")
                stateFailCount = 0
            }
        }
    }

    private fun findAndClickShareInDetail() {
        Log.d(TAG, "已进入帖子详情页，开始寻找底部右侧分享图标（排除三个点）...")
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        
        val rootNode = rootInActiveWindow ?: return
        
        val detailShareKeywords = listOf("分享", "Share", "转发")
        var exactShareNode: AccessibilityNodeInfo? = null
        
        // 收集屏幕最下方（>50%）所有的疑似按钮节点
        val bottomNodes = mutableListOf<AccessibilityNodeInfo>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(rootNode)
        
        while (deque.isNotEmpty()) {
            val node = deque.removeFirst()
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val h = Math.abs(rect.bottom - rect.top)
            val w = Math.abs(rect.right - rect.left)
            
            // 只要在屏幕下半部分 50% 区域，并且具有一定大小的独立节点
            if (rect.centerY() > screenHeight * 0.5 && h in 20..200 && w in 20..(screenWidth / 2)) {
                // 收集所有可能是图标的节点（策略2兜底）
                if (node.isClickable || node.className?.contains("Image") == true || node.className?.contains("Button") == true) {
                    bottomNodes.add(node)
                }
                
                // 策略1：检查是否具有“分享”属性 (隐藏的 accessibility label)
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                
                // 必须是精确匹配或者极短的文本，防止匹配到帖子正文里的长篇大论
                val isShareKeyword = detailShareKeywords.any { 
                    text.equals(it, true) || desc.equals(it, true) ||
                    (text.contains(it, true) && text.length < 15) ||
                    (desc.contains(it, true) && desc.length < 15)
                }
                
                if (isShareKeyword) {
                    if (exactShareNode == null || rect.centerY() > exactShareNode!!.boundsInScreen.centerY()) {
                        exactShareNode = node
                    }
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { deque.add(it) }
            }
        }
        
        if (exactShareNode != null) {
            Log.d(TAG, "🎯 详情页：通过无障碍属性(Aria-Label)精确找到分享图标")
            currentState = State.WAITING
            performClick(exactShareNode)
            scope.launch {
                delay(1500)
                currentState = State.OPENING_SHARE_MENU
                runCurrentStateLogic()
            }
            return
        }
        
        // 策略2：如果图标没有属性（纯盲猜），我们要排除最右侧的“三个点”
        if (bottomNodes.isNotEmpty()) {
            // 过滤掉明确是“更多”、“三个点”的节点
            val filteredNodes = bottomNodes.filter { 
                val text = it.text?.toString() ?: ""
                val desc = it.contentDescription?.toString() ?: ""
                !text.contains("更多") && !desc.contains("更多") &&
                !text.contains("More", true) && !desc.contains("More", true)
            }
            
            if (filteredNodes.isNotEmpty()) {
                // 取最底部的一排节点 (Y坐标相差在40像素以内的算同一排)
                val maxY = filteredNodes.maxOf { it.boundsInScreen.centerY() }
                val bottomRow = filteredNodes.filter { Math.abs(it.boundsInScreen.centerY() - maxY) < 40 }
                
                // 按 X 坐标从左到右排序，并去重（防止同一个按钮的不同图层被多次计算）
                val sortedNodes = bottomRow.sortedBy { it.boundsInScreen.centerX() }
                val uniqueRow = mutableListOf<AccessibilityNodeInfo>()
                for (node in sortedNodes) {
                    if (uniqueRow.isEmpty() || Math.abs(node.boundsInScreen.centerX() - uniqueRow.last().boundsInScreen.centerX()) > 50) {
                        uniqueRow.add(node)
                    }
                }
                
                var targetNode: AccessibilityNodeInfo? = null
                
                // Facebook 标准底部栏：[赞] [评论] [分享] [发送/WhatsApp]
                if (uniqueRow.size >= 3) {
                    // 分享通常是第 3 个
                    targetNode = uniqueRow[2]
                } else if (uniqueRow.size == 2) {
                    // 如果只有2个，选第2个
                    targetNode = uniqueRow[1]
                } else if (uniqueRow.isNotEmpty()) {
                    targetNode = uniqueRow.last()
                }
                
                if (targetNode != null) {
                    Log.d(TAG, "🎯 详情页：通过结构位置找到分享图标（已排除三个点）")
                    currentState = State.WAITING
                    performClick(targetNode)
                    scope.launch {
                        delay(1500)
                        currentState = State.OPENING_SHARE_MENU
                        runCurrentStateLogic()
                    }
                    return
                }
            }
        }
        
        Log.v(TAG, "详情页中暂未发现分享图标，等待...")
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
        val rootNode = rootInActiveWindow
        
        // Facebook 的分享菜单往往是一个底部弹出的 BottomSheet
        // 我们不应该只找确切的文字，应该采用模糊匹配，并且要兼容各种语言
        val groupKeywords = listOf("小组", "Group", "group", "群组", "社团")
        
        // 1. 优先尝试使用原生节点查询（最稳定，不需要截图）
        if (rootNode != null) {
            val candidateNodes = mutableListOf<AccessibilityNodeInfo>()
            val deque = ArrayDeque<AccessibilityNodeInfo>()
            deque.add(rootNode)
            
            while (deque.isNotEmpty()) {
                val node = deque.removeFirst()
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                
                // 只要文字包含“小组”或者“Group”，就认为可能是目标按钮
                if (groupKeywords.any { text.contains(it, ignoreCase = true) }) {
                    candidateNodes.add(node)
                }
                
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { deque.add(it) }
                }
            }
            
            if (candidateNodes.isNotEmpty()) {
                // 优先找可点击的节点，或者找父节点是可点击的
                val validNode = candidateNodes.find { it.isClickable } 
                    ?: candidateNodes.find { it.parent?.isClickable == true }?.parent 
                    ?: candidateNodes[0]
                    
                Log.i(TAG, "🎯 通过原生节点找到‘分享到小组’相关的按钮: ${validNode.text ?: validNode.contentDescription}，执行点击")
                stateFailCount = 0
                currentState = State.WAITING
                performClick(validNode)
                scope.launch {
                    delay(2000) // 等待页面跳转到选择小组列表
                    currentState = State.SELECTING_GROUP
                    runCurrentStateLogic()
                }
                return
            }
        }
        
        // 如果原生节点找不到，很可能是菜单没弹出来，或者被折叠了
        stateFailCount++
        Log.v(TAG, "当前屏幕未发现‘分享到小组’相关的原生节点，尝试向上滚动菜单寻找...")
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScrollTime > 2000) {
            lastScrollTime = currentTime
            scope.launch {
                swipeUp()
            }
        }
        
        if (stateFailCount > 8) {
            Log.w(TAG, "长时间未找到‘分享到小组’，可能点错了或者是不能分享的帖子。放弃本次分享，继续往下滚...")
            stateFailCount = 0
            
            // 我们不直接按系统返回键，因为如果菜单没弹出来，按返回键就会退出浏览器！
            // 解决办法：直接点击屏幕最上方区域（空白处），这可以安全地关掉任何底部弹出的菜单，同时不会触发系统返回退出浏览器。
            val screenWidth = resources.displayMetrics.widthPixels
            performPhysicalClickXY(screenWidth / 2f, 100f)
            
            currentState = State.WAITING
            scope.launch {
                delay(1000)
                currentState = State.FINDING_POST
                runCurrentStateLogic()
            }
        }
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
                currentState = State.WAITING
                performClick(unsharedNode)
                scope.launch {
                    delay(2000) // 等待发布页面完全加载
                    currentState = State.POSTING
                    runCurrentStateLogic()
                }
            } else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastScrollTime > 3000) {
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
        val postKeywords = listOf("发布", "Post", "分享", "Share")
        val screenWidth = resources.displayMetrics.widthPixels
        
        Log.d(TAG, "尝试通过 OCR 寻找右上角发布按钮...")
        
        findTextByOCRAndClick(
            keywords = postKeywords,
            checkCondition = { rect ->
                // 右上角特征：位于屏幕上半部分，且偏右
                rect.top < 400 && rect.centerX() > screenWidth * 0.5
            },
            onSuccess = {
                Log.d(TAG, "OCR点击发布按钮完成，准备返回主页...")
                currentState = State.WAITING
                scope.launch {
                    delay(3000) // 等待发布请求发出
                    
                    // 模拟全局返回操作，退回到主页
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1500)
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1500)
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    delay(1500)
                    
                    currentGroupIndex++
                    // 重复该帖子的下一个小组分享
                    currentState = State.FINDING_POST 
                    Log.d(TAG, "回到寻找帖子状态，准备分享第 ${currentGroupIndex + 1} 个小组")
                    runCurrentStateLogic()
                }
            },
            onNotFound = {
                Log.v(TAG, "OCR 未发现右上角发布文字，可能为图标或仍在加载，启动空间位置探测降级方案...")
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val deque = ArrayDeque<AccessibilityNodeInfo>()
                    deque.add(rootNode)
                    
                    var rightTopBtn: AccessibilityNodeInfo? = null
                    
                    while (deque.isNotEmpty()) {
                        val node = deque.removeFirst()
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        val h = Math.abs(rect.bottom - rect.top)
                        val w = Math.abs(rect.right - rect.left)
                        
                        // 寻找右上角的按钮图标：位于屏幕顶部，偏右，尺寸较小，通常可点击或者是 Image/Button
                        if (rect.top < 400 && rect.right > screenWidth * 0.8 && h in 20..200 && w in 20..200) {
                            if (node.isClickable || node.className?.contains("Image") == true || node.className?.contains("Button") == true) {
                                // 排除掉一些太靠近边缘的系统图标（如关闭、菜单），尽量选靠左一点的发送图标
                                if (rightTopBtn == null || rect.centerX() < rightTopBtn.boundsInScreen.centerX()) {
                                    rightTopBtn = node
                                }
                            }
                        }
                        
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { deque.add(it) }
                        }
                    }
                    
                    if (rightTopBtn != null) {
                        Log.d(TAG, "🎯 通过位置找到右上角疑似发布图标，点击...")
                        currentState = State.WAITING
                        performClick(rightTopBtn)
                        
                        scope.launch {
                            delay(3000)
                            Log.d(TAG, "发布完成，准备返回主页...")
                            
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            delay(1500)
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            delay(1500)
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            delay(1500)
                            
                            currentGroupIndex++
                            currentState = State.FINDING_POST 
                            Log.d(TAG, "回到寻找帖子状态，准备分享第 ${currentGroupIndex + 1} 个小组")
                            runCurrentStateLogic()
                        }
                        return@findTextByOCRAndClick
                    }
                }
                
                stateFailCount++
                if (stateFailCount > 5) {
                    Log.w(TAG, "长时间未找到‘发布’按钮或图标，可能进错了页面，执行返回...")
                    stateFailCount = 0
                    currentState = State.WAITING
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    scope.launch {
                        delay(1500)
                        currentState = State.SELECTING_GROUP
                        runCurrentStateLogic()
                    }
                } else {
                    Log.v(TAG, "当前未发现发布按钮，可能仍在加载...")
                }
            }
        )
    }

    private val textRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private fun findTextByOCRAndClick(
        keywords: List<String>,
        checkCondition: ((Rect) -> Boolean)? = null,
        onSuccess: () -> Unit,
        onNotFound: () -> Unit
    ) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                    if (bitmap == null) {
                        screenshot.hardwareBuffer.close()
                        return onNotFound()
                    }
                    val image = InputImage.fromBitmap(bitmap, 0)
                    textRecognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            var clicked = false
                            for (block in visionText.textBlocks) {
                                val text = block.text
                                if (keywords.any { text.contains(it, ignoreCase = true) }) {
                                    val rect = block.boundingBox
                                    if (rect != null && (checkCondition == null || checkCondition(rect))) {
                                        val x = rect.centerX().toFloat()
                                        val y = rect.centerY().toFloat()
                                        Log.d(TAG, "🎯 OCR锁定 '$text' 坐标: ($x, $y)，执行点击")
                                        performPhysicalClickXY(x, y)
                                        clicked = true
                                        onSuccess()
                                        break
                                    }
                                }
                            }
                            if (!clicked) {
                                onNotFound()
                            }
                            screenshot.hardwareBuffer.close()
                        }
                        .addOnFailureListener {
                            Log.e(TAG, "OCR识别失败: ${it.message}")
                            onNotFound()
                            screenshot.hardwareBuffer.close()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "截图失败，错误码: $errorCode")
                    onNotFound()
                }
            })
        } else {
            Log.e(TAG, "系统版本低于Android 11，无法使用OCR截图功能")
            onNotFound()
        }
    }

    private fun performPhysicalClickXY(x: Float, y: Float) {
        if (x > 0 && y > 0) {
            val path = Path().apply { 
                moveTo(x, y) 
                lineTo(x + 1, y + 1)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            dispatchGesture(gesture, null, null)
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
        val screenHeight = resources.displayMetrics.heightPixels
        val screenWidth = resources.displayMetrics.widthPixels
        
        // 从屏幕底部 80% 快速滑动到顶部 20%
        val path = Path()
        path.moveTo(screenWidth / 2f, screenHeight * 0.8f)
        path.lineTo(screenWidth / 2f, screenHeight * 0.2f)
        
        val gesture = GestureDescription.Builder()
            // 缩短滑动持续时间从 1500ms 到 500ms，让滚动速度变快
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
            
        Log.i(TAG, "正在执行快速滚动手势...")
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                Log.d(TAG, "滚动手势完成")
                // 滚动后延迟一段时间，让页面加载新内容
                scope.launch {
                    delay(1500)
                    runCurrentStateLogic()
                }
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.e(TAG, "手势被系统拦截，请检查是否有覆盖层")
                // 兜底方案：使用节点原生滚动
                val rootNode = rootInActiveWindow
                val scrollableNode = rootNode?.findAccessibilityNodeInfosByText("")?.find { it.isScrollable }
                scrollableNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture 返回 false，手势分发失败")
            val rootNode = rootInActiveWindow
            val scrollableNode = rootNode?.findAccessibilityNodeInfosByText("")?.find { it.isScrollable }
            scrollableNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                ?: rootNode?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
    }

    override fun onInterrupt() {
        instance = null
    }
}
