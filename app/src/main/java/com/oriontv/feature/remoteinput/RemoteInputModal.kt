package com.oriontv.feature.remoteinput

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 远程输入二维码弹窗（ui-design.md §5.8：居中卡片 640dp、圆角 16dp、
 * 60% 黑遮罩、弹窗淡入 150ms；RN RemoteControlModal 对照）：
 * 二维码 220×220 + 服务地址 + 4 位配对码；配对成功后自动关闭（由调用方
 * 订阅 RemoteInputManager.handshakes 后调 onDismiss）。
 * 服务未启动时显示红字原因，不放空白二维码。
 * 焦点（P1-5）：打开时落在主操作上（服务未启动时“一键开启”，已启动时“关闭”）；
 * 方向键在弹窗内循环，不会移到背后页面。
 * 焦点（P1-2，第 5 轮）：服务拉起后“一键开启”按钮消失时，焦点还给“关闭”。
 * 安全（P1-10）：明文提示 + 配对码 5 分钟有效；未启动时可一键开启（P2-14）。
 */
@Composable
fun RemoteInputModal(
    serverUrl: String?,
    pairingCode: String?,
    error: String?,
    onDismiss: () -> Unit,
    /** 服务未启动时的一键开启回调（P2-14）；null = 不显示该按钮。 */
    onEnableRemoteInput: (() -> Unit)? = null,
    /** 当前配对码剩余有效秒数（P2-8 倒计时；null = 未签发，显示“5 分钟内有效”）。 */
    pairingCodeRemainingSeconds: Int? = null,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val closeRequester = remember { FocusRequester() }
    val enableRequester = remember { FocusRequester() }

    // C4-P1-3：扫码弹窗处理返回键（之前按返回直接走默认返回，弹窗不关、页面被 pop）。
    BackHandler { onDismiss() }

    LaunchedEffect(serverUrl) {
        qrBitmap = if (serverUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { QrCodeGenerator.generateBitmap(serverUrl) }
        }
    }
    // 第 6 轮 P2-9：showEnableButton 须在初始焦点 effect 之前算好——打开即落主操作。
    val showEnableButton = onEnableRemoteInput != null && serverUrl.isNullOrBlank()

    // P1-5：打开即抢焦点到主操作按钮。
    // 第 6 轮 P2-9：之前恒抢“关闭”——服务未启动时（正是从搜索页点“手机扫码输入”
    // 最常见的状态）用户需先横移一格才能点“开启远程输入”；现在未启动落“一键开启”，
    // 已启动落“关闭”。服务拉起后按钮消失的回落仍由下面的 showEnableButton effect 管。
    LaunchedEffect(Unit) {
        if (showEnableButton) enableRequester.requestFocus() else closeRequester.requestFocus()
    }

    // P1-2（第 5 轮）：服务拉起后“一键开启”按钮消失，焦点随之清除；
    // 状态翻转后把焦点还给“关闭”按钮，不只打开瞬间抢一次。
    LaunchedEffect(showEnableButton) {
        if (!showEnableButton) closeRequester.requestFocus()
    }

    // C6-P2-1：删掉恒 visible=true 的 AnimatedVisibility——弹窗由调用方
    // `if (showRemoteInputModal)` 条件组合，卸载时整棵子树直接移除，exit
    // 动画永远跑不到；留着只是死代码。
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
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = "手机扫码输入", style = OrionTextStyles.SectionTitle)
                Spacer(modifier = Modifier.height(16.dp))
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap!!.asImageBitmap(),
                        contentDescription = "远程输入二维码",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .width(220.dp)
                            .height(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(8.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "用手机扫码，在手机浏览器里输入文字，实时同步到电视搜索框。",
                        style = OrionTextStyles.Body,
                        color = OrionColors.TextSecondary,
                    )
                    if (!serverUrl.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = serverUrl, style = OrionTextStyles.Small)
                    }
                    if (!pairingCode.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            // P2-8：倒计时（轮换任务每 5 秒刷新）；没拿到剩余秒数时
                            // 回落到旧文案“5 分钟内有效”。
                            text = "配对码：$pairingCode（${
                                RemoteInputLogic.formatPairingRemaining(
                                    pairingCodeRemainingSeconds,
                                ) ?: "5 分钟内有效"
                            }）",
                            style = OrionTextStyles.SectionTitle,
                            color = OrionColors.Brand,
                        )
                    }
                } else {
                    Text(
                        text = error ?: "远程输入服务未启动，请先在设置里开启远程输入。",
                        style = OrionTextStyles.Body,
                        color = if (error != null) OrionColors.Error else OrionColors.TextSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // P1-10：明文服务提示（局域网内可被嗅探，配对码 5 分钟过期）。
                Text(
                    text = "注意：这是局域网明文服务，同一网络下的设备可以看到传输内容；配对码 5 分钟内有效，请勿在公共网络使用。",
                    style = OrionTextStyles.Small,
                    color = OrionColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showEnableButton) {
                        PrimaryButton(
                            text = "开启远程输入",
                            onClick = { onEnableRemoteInput?.invoke() },
                            // P1-5 焦点陷阱：两按钮间左右循环，上下回“关闭”，不出弹窗。
                            modifier = Modifier
                                .focusRequester(enableRequester)
                                .focusProperties {
                                    left = closeRequester
                                    right = closeRequester
                                    up = closeRequester
                                    down = closeRequester
                                },
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    PrimaryButton(
                        text = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(closeRequester)
                            .focusProperties {
                                left = if (showEnableButton) enableRequester else closeRequester
                                right = if (showEnableButton) enableRequester else closeRequester
                                up = closeRequester
                                down = closeRequester
                            },
                    )
                }
            }
        }
}
