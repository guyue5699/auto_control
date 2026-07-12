package com.example.fbsharer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fbsharer.data.PostTask
import com.example.fbsharer.data.TaskStatus
import com.example.fbsharer.ui.viewmodel.TaskViewModel

import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
import com.example.fbsharer.utils.PermissionUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onNavigateToCreateTask: () -> Unit,
    viewModel: TaskViewModel = viewModel()
) {
    val tasks by viewModel.allTasks.collectAsState(initial = emptyList())
    val context = LocalContext.current
    val isPermissionEnabled = PermissionUtils.isAccessibilityServiceEnabled(context)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("发布任务列表") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCreateTask) {
                Icon(Icons.Default.Add, contentDescription = "新建任务")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (!isPermissionEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) {
                    Text(
                        text = "请点击开启无障碍服务以使用自动分享功能",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tasks) { task ->
                    TaskItem(task, onStart = { viewModel.startTask(task) })
                }
            }
        }
    }
}

@Composable
fun TaskItem(task: PostTask, onStart: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "链接: ${task.targetUrl}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
                if (task.text.isNotBlank()) {
                    Text(text = "内容: ${task.text}", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                Text(text = "目标群组: ${task.groupNames}", style = MaterialTheme.typography.bodySmall)
                Text(text = "状态: ${task.status}", color = getStatusColor(task.status))
            }
            if (task.status == TaskStatus.PENDING) {
                IconButton(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "开始执行", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
fun getStatusColor(status: TaskStatus) = when (status) {
    TaskStatus.PENDING -> MaterialTheme.colorScheme.outline
    TaskStatus.RUNNING -> MaterialTheme.colorScheme.primary
    TaskStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    TaskStatus.FAILED -> MaterialTheme.colorScheme.error
}
