package com.example.fbsharer.automation

import android.webkit.JavascriptInterface
import android.util.Log
import android.os.Handler
import android.os.Looper

class FBJSInterface(private val onLog: (String) -> Unit, private val onTaskComplete: () -> Unit) {
    
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun log(message: String) {
        Log.d("FBAutomation", "JS Log: $message")
        mainHandler.post {
            onLog(message)
        }
    }
    
    @JavascriptInterface
    fun onComplete() {
        Log.d("FBAutomation", "Task Complete")
        mainHandler.post {
            onTaskComplete()
        }
    }
    
    @JavascriptInterface
    fun onError(error: String) {
        Log.e("FBAutomation", "JS Error: $error")
        mainHandler.post {
            onLog("ERROR: $error")
        }
    }
}
