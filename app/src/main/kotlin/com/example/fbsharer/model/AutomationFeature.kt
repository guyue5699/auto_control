package com.example.fbsharer.model

import androidx.compose.ui.graphics.vector.ImageVector

data class AutomationFeature(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val route: String,
    val description: String,
    val isEnabled: Boolean = true
)
