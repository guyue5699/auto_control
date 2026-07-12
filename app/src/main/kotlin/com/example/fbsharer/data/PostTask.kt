package com.example.fbsharer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "post_tasks")
data class PostTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val targetUrl: String, // Added field for post link
    val imagePaths: String, // JSON string of List<String>
    val groupNames: String, // Comma separated group names
    val status: TaskStatus = TaskStatus.PENDING,
    val logs: String = "", // Added field for execution logs
    val createdAt: Long = System.currentTimeMillis()
)

enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED
}
