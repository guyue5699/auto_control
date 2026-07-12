package com.example.fbsharer.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fbsharer.ui.viewmodel.TaskViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTaskScreen(
    onBack: () -> Unit,
    viewModel: TaskViewModel = viewModel()
) {
    var text by remember { mutableStateOf("") }
    var targetUrl by remember { mutableStateOf("") }
    var groups by remember { mutableStateOf("") }
    var imagePaths by remember { mutableStateOf("") }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新建分享任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = targetUrl,
                onValueChange = { targetUrl = it },
                label = { Text("Facebook 帖子链接 (必填)") },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("附带文字内容 (可选)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )

            OutlinedTextField(
                value = groups,
                onValueChange = { groups = it },
                label = { Text("目标群组名称 (多个用逗号分隔)") },
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val pathList = if (imagePaths.isBlank()) emptyList() else imagePaths.split(",").map { it.trim() }
                    viewModel.addTask(text, targetUrl, pathList, groups)
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = targetUrl.isNotBlank() && groups.isNotBlank()
            ) {
                Text("保存任务")
            }
        }
    }
}
