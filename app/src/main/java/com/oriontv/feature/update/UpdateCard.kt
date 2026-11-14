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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 更新卡片（ui-design.md §5.7/§5.8：居中卡片 640dp、圆角 16dp、
 * 60% 黑遮罩、弹窗淡入 150ms）：
 * 版本号 / 大小 / 立即更新 / 跳过此版。
 * 下载中显示进度，下载完成变“安装”；失败原因红字显示在卡片内。
 * 焦点（P1-5）：打开时 requestFocus 到主按钮（“立即更新/立即安装”）；
 * 三个按钮间方向键循环，不会移到背后页面；关闭后焦点由调用方还给触发点。
 * 焦点（P1-2，第 5 轮）：焦点随卡片状态走，不只打开瞬间抢一次——
 * 下载完成变“立即安装”时落回主按钮；超限包打开时主按钮禁用，直接落
 * 到“跳过此版”；下载中主按钮禁用，落到“稍后”（下载继续在后台跑）。
 * P2-21：旧写死通用“更新说明”已去掉（服务端不返回 changelog 时不编文案）。
 * P2-22：安装包超 200MB 时禁用更新按钮并提示（下载层原有拦截，卡片层提前拦）。
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
    val primaryRequester = remember { FocusRequester() }
    val skipRequester = remember { FocusRequester() }
    val dismissRequester = remember { FocusRequester() }

    // P2-22：超 200MB 的包卡片层直接禁用更新按钮（不让用户点进去才报 TooLarge）。
    val apkTooLarge = apkSizeBytes != null && apkSizeBytes > UpdateDownloader.MAX_APK_BYTES

    // P1-2（第 5 轮）：焦点随卡片状态走，不只打开瞬间抢一次。
    // 下载完成变“立即安装”时落回主按钮；超限包打开时主按钮禁用，直接落
    // 到“跳过此版”；下载中主按钮禁用，落到“稍后”（下载继续在后台跑）。
    LaunchedEffect(downloading, downloaded, apkTooLarge) {
        when {
            downloaded -> primaryRequester.requestFocus()
            downloading -> dismissRequester.requestFocus()
            apkTooLarge -> skipRequester.requestFocus()
            else -> primaryRequester.requestFocus()
        }
    }

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
                if (apkTooLarge) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "安装包超过 200MB 上限，无法下载更新",
                        style = OrionTextStyles.Body,
                        color = OrionColors.Error,
                    )
                }
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
                    // P1-5 焦点陷阱：三按钮左右循环，上下回主按钮，不出弹窗。
                    when {
                        downloaded -> PrimaryButton(
                            text = "立即安装",
                            onClick = onInstall,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = skipRequester
                                    up = primaryRequester
                                    down = primaryRequester
                                },
                        )
                        downloading -> PrimaryButton(
                            text = "下载中…",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = skipRequester
                                    up = primaryRequester
                                    down = primaryRequester
                                },
                        )
                        apkTooLarge -> PrimaryButton(
                            text = "安装包过大",
                            onClick = {},
                            enabled = false,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = skipRequester
                                    up = primaryRequester
                                    down = primaryRequester
                                },
                        )
                        else -> PrimaryButton(
                            text = "立即更新",
                            onClick = onUpdate,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = skipRequester
                                    up = primaryRequester
                                    down = primaryRequester
                                },
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    SecondaryButton(
                        text = "跳过此版",
                        onClick = onSkip,
                        modifier = Modifier
                            .focusRequester(skipRequester)
                            .focusProperties {
                                left = primaryRequester
                                right = dismissRequester
                                up = primaryRequester
                                down = primaryRequester
                            },
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    SecondaryButton(
                        text = "稍后",
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(dismissRequester)
                            .focusProperties {
                                left = skipRequester
                                right = primaryRequester
                                up = primaryRequester
                                down = primaryRequester
                            },
                    )
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
