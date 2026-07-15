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
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                }
                
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLog("页面加载完成: $url")
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
