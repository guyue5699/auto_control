package com.example.fbsharer

import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.fbsharer.ui.screens.CreateTaskScreen
import com.example.fbsharer.ui.screens.HomeScreen
import com.example.fbsharer.ui.screens.TaskListScreen
import com.example.fbsharer.ui.screens.execution.TaskExecutionScreen
import com.example.fbsharer.data.PostTask

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 Cookie 管理器
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(null, true) // 全局启用
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()
                }
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToFeature = { route -> navController.navigate(route) }
            )
        }
        composable("facebook_task_list") {
            TaskListScreen(
                onNavigateToCreateTask = { navController.navigate("facebook_create_task") },
                onStartTask = { taskJson -> 
                    // 这里为了简单起见，我们直接传递 ID 并在执行页读取，或者使用共享 ViewModel
                    // 这里假设我们有一个简单的方法获取任务对象
                    navController.navigate("facebook_execute_task/${taskJson.id}")
                }
            )
        }
        composable("facebook_execute_task/{taskId}") { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId")?.toLongOrNull() ?: 0L
            // 在实际项目中，这里应该从 ViewModel 获取 Task
            // 为了演示流程，我们暂时构造一个简单的逻辑
            TaskExecutionScreen(
                task = PostTask(id = taskId, text = "", targetUrl = "", imagePaths = "", groupNames = ""),
                onClose = { navController.popBackStack() }
            )
        }
        composable("facebook_create_task") {
            CreateTaskScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
