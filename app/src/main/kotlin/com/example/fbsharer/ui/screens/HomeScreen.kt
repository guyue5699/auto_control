package com.example.fbsharer.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.fbsharer.model.AutomationFeature

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToFeature: (String) -> Unit,
    viewModel: com.example.fbsharer.ui.viewmodel.TaskViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val isPermissionEnabled = com.example.fbsharer.utils.PermissionUtils.isAccessibilityServiceEnabled(context)

    val features = listOf(
        AutomationFeature(
            id = "facebook",
            title = "Facebook 自动分享",
            icon = Icons.Default.Share,
            route = "facebook_direct",
            description = "一键自动分享个人帖子到所有小组"
        ),
        AutomationFeature(
            id = "tiktok",
            title = "TikTok 自动化",
            icon = Icons.Default.PlayArrow,
            route = "tiktok_placeholder",
            description = "自动点赞、评论与关注 (开发中)",
            isEnabled = false
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("自动化群控") })
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (!isPermissionEnabled) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) {
                    Text(
                        text = "请点击开启无障碍服务以开启自动化群控功能",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center
                    )
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(features) { feature ->
                    FeatureCard(feature, onClick = {
                        if (feature.isEnabled) {
                            if (feature.id == "facebook") {
                                viewModel.startDirectAutomation()
                            } else {
                                onNavigateToFeature(feature.route)
                            }
                        }
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureCard(feature: AutomationFeature, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
        enabled = feature.isEnabled,
        colors = CardDefaults.cardColors(
            containerColor = if (feature.isEnabled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (feature.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = feature.title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}
