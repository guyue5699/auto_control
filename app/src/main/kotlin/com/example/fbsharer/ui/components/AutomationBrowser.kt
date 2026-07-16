package com.example.fbsharer.ui.components

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fbsharer.automation.FBJSInterface

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AutomationBrowser(
    url: String,
    modifier: Modifier = Modifier,
    onLog: (String) -> Unit,
    onTaskComplete: () -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // 修正：禁用这些可能导致页面缩放异常的设置
                    useWideViewPort = false
                    loadWithOverviewMode = false
                    // 允许缩放但隐藏控件
                    setSupportZoom(true)
                    builtInZoomControls = false
                    displayZoomControls = false
                    
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                }
                
                // 修正：确保滚动条不占位
                scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_OVERLAY
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLog("页面加载完成: $url")
                        // 尝试自动注入脚本
                        try {
                            val script = context.assets.open("fb_automation.js").bufferedReader().use { it.readText() }
                            // 修正：确保脚本返回明确的成功标志，并使用 String 转化
                            view?.evaluateJavascript("(function(){ $script; return 'SUCCESS'; })();") { result ->
                                onLog("脚本注入状态: ${result ?: "null"}")
                            }
                        } catch (e: Exception) {
                            onLog("ERROR: 脚本注入失败: ${e.message}")
                        }
                    }
                }
                
                webChromeClient = WebChromeClient()
                
                addJavascriptInterface(FBJSInterface(onLog, onTaskComplete), "AndroidBridge")
                
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                
                onWebViewCreated(this)
                loadUrl(url)
            }
        }
    )
}
