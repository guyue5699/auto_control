package com.example.fbsharer

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                onNavigateToCreateTask = { navController.navigate("facebook_create_task") }
            )
        }
        composable("facebook_create_task") {
            CreateTaskScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
