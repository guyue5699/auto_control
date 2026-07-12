package com.example.fbsharer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PostTaskDao {
    @Query("SELECT * FROM post_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<PostTask>>

    @Query("SELECT * FROM post_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): PostTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: PostTask): Long

    @Update
    suspend fun updateTask(task: PostTask)

    @Delete
    suspend fun deleteTask(task: PostTask)
    
    @Query("UPDATE post_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: TaskStatus)

    @Query("UPDATE post_tasks SET logs = :logs WHERE id = :id")
    suspend fun updateLogs(id: Long, logs: String)
}
