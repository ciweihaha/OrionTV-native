package com.oriontv.feature.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 更新卡片（ui-design.md §5.7/§5.8：居中卡片 640dp、圆角 16dp、
 * 60% 黑遮罩、弹窗淡入 150ms；焦点默认落在主按钮）：
 * 版本号 / 大小 / 更新说明 / 立即更新 / 跳过此版。
 * 下载中显示进度，下载完成变“安装”；失败原因红字显示在卡片内。
 */
@Composable
fun UpdateCard(
    currentVersion: String,
    latestVersion: String,
    apkSizeBytes: Long?,
    downloading: Boolean,
    downloadProgress: Int,
    downloaded: Boolean,
    error: String?,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(animationSpec = tween(150)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(OrionColors.Background.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(OrionDimens.DialogWidth)
                    .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                    .background(OrionColors.Overlay)
                    .border(
                        width = 1.dp,
                        color = OrionColors.Divider,
                        shape = RoundedCornerShape(OrionDimens.PanelCornerRadius),
                    )
                    .padding(24.dp),
            ) {
                Text(text = "发现新版本 v$latestVersion", style = OrionTextStyles.SectionTitle)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "当前版本：v$currentVersion",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "大小：${apkSizeBytes?.let { formatApkSize(it) } ?: "下载时获取"}",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "更新说明：OrionTV v$latestVersion，修复已知问题，提升稳定性。",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
                if (downloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "正在下载… $downloadProgress%",
                        style = OrionTextStyles.Body,
                        color = OrionColors.Brand,
                    )
                }
                if (error != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = error, style = OrionTextStyles.Body, color = OrionColors.Error)
                }
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        downloaded -> PrimaryButton(text = "立即安装", onClick = onInstall)
                        downloading -> PrimaryButton(text = "下载中…", onClick = {}, enabled = false)
                        else -> PrimaryButton(text = "立即更新", onClick = onUpdate)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    SecondaryButton(text = "跳过此版", onClick = onSkip)
                    Spacer(modifier = Modifier.width(16.dp))
                    SecondaryButton(text = "稍后", onClick = onDismiss)
                }
            }
        }
    }
}

private fun formatApkSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1fKB".format(bytes / 1024f)
    else -> "${bytes}B"
}
