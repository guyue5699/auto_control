package com.example.fbsharer.ui.screens.execution

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fbsharer.data.PostTask
import com.example.fbsharer.ui.components.AutomationBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskExecutionScreen(
    task: PostTask,
    onClose: () -> Unit
) {
    var logs by remember { mutableStateOf(listOf("正在初始化浏览器...")) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部控制栏
        TopAppBar(
            title = { Text("任务执行中", fontSize = 18.sp) },
            actions = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "停止任务")
                }
            }
        )
        
        // 内嵌浏览器预览 (占比 70%)
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(0.7f)
            .background(Color.Black)
        ) {
            AutomationBrowser(
                url = "https://m.facebook.com/profile.php",
                modifier = Modifier.fillMaxSize(),
                onLog = { newLog ->
                    logs = logs + newLog
                },
                onTaskComplete = {
                    logs = logs + "任务全部完成！"
                },
                onWebViewCreated = { webView ->
                    webViewInstance = webView
                }
            )
        }
        
        // 实时控制台日志 (占比 30%)
        Column(modifier = Modifier
            .fillMaxWidth()
            .weight(0.3f)
            .background(Color(0xFF1E1E1E))
            .padding(8.dp)
        ) {
            Text("实时日志控制台", color = Color.Gray, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Divider(color = Color.DarkGray)
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                reverseLayout = true
            ) {
                items(logs.reversed()) { log ->
                    Text(
                        text = "> $log",
                        color = when {
                            log.contains("ERROR") -> Color.Red
                            log.contains("注入") || log.contains("加载") -> Color.Yellow
                            else -> Color.Green
                        },
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
        
        // 底部操作区
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(
                    onClick = {
                        logs = logs + "正在注入自动化脚本..."
                        webViewInstance?.loadUrl("javascript:window.runFBAutomation()")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("手动触发注入脚本")
                }
            }
        }
    }
}
