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

    fun startDirectAutomation() {
        viewModelScope.launch {
            val service = com.example.fbsharer.service.FBAutomationService.instance
            if (service == null) {
                Toast.makeText(getApplication(), "无障碍服务未启动，请先开启权限", Toast.LENGTH_LONG).show()
                return@launch
            }
            
            // 构造默认任务：跳转到个人主页，不指定群组（表示遍历所有群组）
            val directTask = PostTask(
                text = "",
                targetUrl = "https://m.facebook.com/profile.php",
                imagePaths = "",
                groupNames = "" 
            )
            service.startAutomation(directTask)
        }
    }

    fun startTask(task: PostTask) {
        viewModelScope.launch {
            val service = com.example.fbsharer.service.FBAutomationService.instance
            if (service == null) {
                Toast.makeText(getApplication(), "无障碍服务未启动，请先开启权限", Toast.LENGTH_LONG).show()
                return@launch
            }
            dao.updateStatus(task.id, TaskStatus.RUNNING)
            service.startAutomation(task)
        }
    }

    fun deleteTask(task: PostTask) {
        viewModelScope.launch {
            dao.deleteTask(task)
        }
    }
}
