package com.example.fbsharer.automation

import android.webkit.JavascriptInterface
import android.util.Log

class FBJSInterface(private val onLog: (String) -> Unit, private val onTaskComplete: () -> Unit) {
    
    @JavascriptInterface
    fun log(message: String) {
        Log.d("FBAutomation", "JS Log: $message")
        onLog(message)
    }
    
    @JavascriptInterface
    fun onComplete() {
        Log.d("FBAutomation", "Task Complete")
        onTaskComplete()
    }
    
    @JavascriptInterface
    fun onError(error: String) {
        Log.e("FBAutomation", "JS Error: $error")
        onLog("ERROR: $error")
    }
}
