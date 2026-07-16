package com.example.fbsharer.ui.viewmodel

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.fbsharer.data.AppDatabase
import com.example.fbsharer.data.PostTask
import com.example.fbsharer.data.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = AppDatabase.getDatabase(application).postTaskDao()
    val allTasks: Flow<List<PostTask>> = dao.getAllTasks()

    fun addTask(text: String, targetUrl: String, imagePaths: List<String>, groups: String) {
        viewModelScope.launch {
            val task = PostTask(
                text = text,
                targetUrl = targetUrl,
                imagePaths = imagePaths.joinToString(","),
                groupNames = groups,
                status = TaskStatus.PENDING
            )
            dao.insertTask(task)
        }
    }

    fun startTask(task: PostTask) {
        viewModelScope.launch {
            dao.updateStatus(task.id, TaskStatus.RUNNING)
            // 现在通过 TaskExecutionScreen 中的 WebView 执行，不再通过 Service
        }
    }

    fun deleteTask(task: PostTask) {
        viewModelScope.launch {
            dao.deleteTask(task)
        }
    }
}
