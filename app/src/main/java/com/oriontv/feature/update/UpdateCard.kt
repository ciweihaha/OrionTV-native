package com.oriontv.feature.update

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
import com.oriontv.ui.components.ModalFadeIn
import com.oriontv.ui.components.consumeTaps
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
 * 三个按钮间方向键循环，不会移到背后页面；关闭后焦点由 NavHost 的 focusRestoreTick 还给当前页默认锚点（设置页回左菜单当前项），不回触发按钮。
 * 焦点（P1-2，第 5 轮）：焦点随卡片状态走，不只打开瞬间抢一次——
 * 下载完成变“立即安装”时落回主按钮；超限包打开时主按钮禁用，直接落
 * 到“跳过此版”；下载中主按钮禁用，落到“取消下载”（下载继续在后台跑
 * 的是“稍后”，不是取消——见 B6-P1-3 语义）。
 * P2-21：旧写死通用“更新说明”已去掉（服务端不返回 changelog 时不编文案）。
 * P2-22：安装包超 200MB 时禁用更新按钮并提示（下载层原有拦截，卡片层提前拦）。
 * B6-P1-3：下载中卡片上有“取消下载”按钮（接 UpdateViewModel.cancelDownload）——
 * 取消只停本次下载，卡片保持打开，可点“立即更新”重新下载；“稍后”是关卡、
 * 下载继续在后台跑，两者语义不同，不可互相替代。
 */
/**
 * 更新卡片焦点陷阱上下边的落点（可单测，C7-P2-1）：禁用的主按钮不能当落点——
 * 下载中指“取消下载”（当前唯一可操作的主操作），超限指“跳过此版”，否则指主按钮。
 * 旧实现四条边（主按钮上下、跳过上下、稍后上下）全指主按钮，禁用态下全变死键。
 */
internal enum class UpdateCardTrapTarget { Primary, Cancel, Skip }

internal fun updateCardTrapTarget(downloading: Boolean, apkTooLarge: Boolean): UpdateCardTrapTarget =
    when {
        downloading -> UpdateCardTrapTarget.Cancel
        apkTooLarge -> UpdateCardTrapTarget.Skip
        else -> UpdateCardTrapTarget.Primary
    }

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
    onCancelDownload: () -> Unit = {},
) {
    val primaryRequester = remember { FocusRequester() }
    val skipRequester = remember { FocusRequester() }
    val dismissRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    // P2-22：超 200MB 的包卡片层直接禁用更新按钮（不让用户点进去才报 TooLarge）。
    val apkTooLarge = apkSizeBytes != null && apkSizeBytes > UpdateDownloader.MAX_APK_BYTES

    // C7-P2-1：陷阱上下边的落点随卡片状态走——禁用态的主按钮不当落点。
    val trapRequester = when (updateCardTrapTarget(downloading, apkTooLarge)) {
        UpdateCardTrapTarget.Primary -> primaryRequester
        UpdateCardTrapTarget.Cancel -> cancelRequester
        UpdateCardTrapTarget.Skip -> skipRequester
    }

    // P1-2（第 5 轮）：焦点随卡片状态走，不只打开瞬间抢一次。
    // 下载完成变“立即安装”时落回主按钮；超限包打开时主按钮禁用，直接落
    // 到“跳过此版”；下载中主按钮禁用，落到“取消下载”（B6-P1-3，下载中
    // 可取消是首要操作；“稍后”在它左边，仍可方向键到达）。
    LaunchedEffect(downloading, downloaded, apkTooLarge) {
        when {
            downloaded -> primaryRequester.requestFocus()
            downloading -> cancelRequester.requestFocus()
            apkTooLarge -> skipRequester.requestFocus()
            else -> primaryRequester.requestFocus()
        }
    }

    // C6-P2-1：删掉恒 visible=true 的 AnimatedVisibility——卡片由调用方
    // `if (showUpdateCard)` 条件组合，卸载时整棵子树直接移除，exit 动画
    // 永远跑不到；留着只是死代码。
    // C11-P2-3：入场淡入 150ms（设计稿 §4）——KDoc 一直写着淡入，代码却没有；
    // 用 ModalFadeIn（MutableTransitionState false → true），只做入场、不改焦点。
    ModalFadeIn {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OrionColors.Background.copy(alpha = 0.6f))
            // B29-P2-1：更新卡片空白区点按在此消费掉，不漏到视频点按层。
            .consumeTaps(),
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
                    // P1-5 焦点陷阱：按钮左右循环，上下回主按钮，不出弹窗。
                    when {
                        downloaded -> PrimaryButton(
                            text = "立即安装",
                            onClick = onInstall,
                            modifier = Modifier
                                .focusRequester(primaryRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    right = skipRequester
                                    up = trapRequester
                                    down = trapRequester
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
                                    up = trapRequester
                                    down = trapRequester
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
                                    up = trapRequester
                                    down = trapRequester
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
                                    up = trapRequester
                                    down = trapRequester
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
                                // C7-P2-1：主按钮禁用时左键不指它——下载中指“取消下载”，
                                // 超限指“稍后”（左右循环仍闭环在可用按钮上）。
                                left = when {
                                    downloading -> cancelRequester
                                    apkTooLarge -> dismissRequester
                                    else -> primaryRequester
                                }
                                right = dismissRequester
                                up = trapRequester
                                down = trapRequester
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
                                // C7-P2-1：右键不指禁用的主按钮——下载中指“取消下载”，
                                // 超限指“跳过此版”。
                                right = when {
                                    downloading -> cancelRequester
                                    apkTooLarge -> skipRequester
                                    else -> primaryRequester
                                }
                                up = trapRequester
                                down = trapRequester
                            },
                    )
                    // B6-P1-3：下载中才出现“取消下载”——停本次下载，卡片保持打开，
                    // 可点“立即更新”重新下载；与“稍后”（关卡、后台继续下）语义不同。
                    if (downloading) {
                        Spacer(modifier = Modifier.width(16.dp))
                        SecondaryButton(
                            text = "取消下载",
                            onClick = onCancelDownload,
                            modifier = Modifier
                                .focusRequester(cancelRequester)
                                .focusProperties {
                                    left = dismissRequester
                                    // C7-P2-1：右键不指禁用的主按钮，改指“跳过此版”。
                                    right = skipRequester
                                    up = trapRequester
                                    down = trapRequester
                                },
                        )
                    }
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
