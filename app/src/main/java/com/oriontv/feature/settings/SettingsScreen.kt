package com.oriontv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.ui.components.ConfirmDialog
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.orionFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/** 设置左菜单（ui-design.md §5.7）：源地址 / 播放设置 / 远程输入 / 检查更新 / 关于。 */
private val SettingsMenu = listOf("源地址", "播放设置", "远程输入", "检查更新", "关于")

/**
 * 设置页（ui-design.md §5.7）：左右两栏。
 * 左菜单列表，右内容区随左菜单切换；焦点在左菜单，右键进右栏，左键回左菜单
 * （方向键移动由系统焦点链处理）；
 * 源地址：API + M3U 两行输入，保存立即生效并清除登录态；
 * 播放设置：下集预告、跳过片头、倍速记忆三个开关；
 * 检查更新：当前版本 + “检查更新”按钮，有新版弹出更新卡片。
 * M3 补：真实读写 SettingsRepository、版本检查、登录态清除。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    var selectedMenu by remember { mutableStateOf(0) }
    var apiUrl by remember { mutableStateOf("") }
    var m3uUrl by remember { mutableStateOf("") }
    var nextPreview by remember { mutableStateOf(true) }
    var skipIntro by remember { mutableStateOf(true) }
    var speedMemory by remember { mutableStateOf(true) }
    var showUpdateCard by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(OrionDimens.ContentPadding),
    ) {
        // 左：菜单列表。
        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "设置", style = OrionTextStyles.PageTitle)
            Spacer(modifier = Modifier.height(16.dp))
            SettingsMenu.forEachIndexed { index, label ->
                SettingsMenuItem(
                    label = label,
                    selected = selectedMenu == index,
                    onClick = { selectedMenu = index },
                )
            }
        }

        // 右：内容区。
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(start = 48.dp),
        ) {
            when (selectedMenu) {
                0 -> SourceSettings(
                    apiUrl = apiUrl,
                    onApiUrlChange = { apiUrl = it },
                    m3uUrl = m3uUrl,
                    onM3uUrlChange = { m3uUrl = it },
                    onSave = { /* M3：保存并清除登录态 */ },
                )
                1 -> PlaybackSettings(
                    nextPreview = nextPreview,
                    onNextPreviewChange = { nextPreview = it },
                    skipIntro = skipIntro,
                    onSkipIntroChange = { skipIntro = it },
                    speedMemory = speedMemory,
                    onSpeedMemoryChange = { speedMemory = it },
                )
                2 -> RemoteInputSettings()
                3 -> UpdateSettings(onCheckUpdate = { showUpdateCard = true })
                4 -> AboutSettings()
            }
        }
    }

    if (showUpdateCard) {
        ConfirmDialog(
            title = "发现新版本 v2.0.1",
            message = "大小：18MB\n更新内容：修复已知问题，提升稳定性。",
            confirmText = "立即更新",
            cancelText = "跳过此版",
            onConfirm = { showUpdateCard = false /* M3：走 UpdateService 下载安装 */ },
            onCancel = { showUpdateCard = false },
        )
    }
}

@Composable
private fun SettingsMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused)
            .background(
                if (selected) OrionColors.Surface else OrionColors.Background,
                RoundedCornerShape(OrionDimens.CardCornerRadius),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = OrionTextStyles.Body,
            color = if (selected) OrionColors.Brand else OrionColors.Text,
        )
    }
}

/** 源地址：API + M3U 两行输入，保存立即生效并清除登录态。 */
@Composable
private fun SourceSettings(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    m3uUrl: String,
    onM3uUrlChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "源地址", style = OrionTextStyles.SectionTitle)
        SettingsInputRow(label = "API 地址", value = apiUrl, onValueChange = onApiUrlChange)
        SettingsInputRow(label = "M3U 地址", value = m3uUrl, onValueChange = onM3uUrlChange)
        PrimaryButton(text = "保存", onClick = onSave)
        Text(text = "保存后立即生效，并清除登录状态。", style = OrionTextStyles.Small)
    }
}

@Composable
private fun SettingsInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(text = label, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrionDimens.CardCornerRadius))
                .background(OrionColors.Surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    if (value.isEmpty()) {
                        Text(text = "请输入$label", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                    }
                    innerTextField()
                },
            )
        }
    }
}

/** 播放设置：下集预告、跳过片头、倍速记忆三个开关。 */
@Composable
private fun PlaybackSettings(
    nextPreview: Boolean,
    onNextPreviewChange: (Boolean) -> Unit,
    skipIntro: Boolean,
    onSkipIntroChange: (Boolean) -> Unit,
    speedMemory: Boolean,
    onSpeedMemoryChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "播放设置", style = OrionTextStyles.SectionTitle)
        ToggleRow(label = "下集预告", checked = nextPreview, onToggle = { onNextPreviewChange(!nextPreview) })
        ToggleRow(label = "跳过片头", checked = skipIntro, onToggle = { onSkipIntroChange(!skipIntro) })
        ToggleRow(label = "倍速记忆", checked = speedMemory, onToggle = { onSpeedMemoryChange(!speedMemory) })
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                    onToggle()
                    true
                } else {
                    false
                }
            }
            .orionFocus(focused)
            .background(OrionColors.Surface, RoundedCornerShape(OrionDimens.CardCornerRadius))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = OrionTextStyles.Body)
        Text(
            text = if (checked) "开" else "关",
            style = OrionTextStyles.Body,
            color = if (checked) OrionColors.Brand else OrionColors.TextSecondary,
        )
    }
}

/** 远程输入：手机扫码后在手机上打字（端口 12346，RemoteInputService）。 */
@Composable
private fun RemoteInputSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "远程输入", style = OrionTextStyles.SectionTitle)
        Text(text = "用手机扫码，在手机上输入文字，实时同步到电视。", style = OrionTextStyles.Body, color = OrionTextStyles.Body.color)
        Box(
            modifier = Modifier
                .width(200.dp)
                .height(200.dp)
                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                .background(OrionColors.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "二维码（M3 接）", style = OrionTextStyles.Small)
        }
        Text(text = "服务端口：12346", style = OrionTextStyles.Small)
    }
}

/** 检查更新：当前版本 + “检查更新”按钮。 */
@Composable
private fun UpdateSettings(onCheckUpdate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "检查更新", style = OrionTextStyles.SectionTitle)
        Text(text = "当前版本：v2.0.0", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        SecondaryButton(text = "检查更新", onClick = onCheckUpdate)
    }
}

/** 关于：版本与包名。 */
@Composable
private fun AboutSettings() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "关于", style = OrionTextStyles.SectionTitle)
        Text(text = "OrionTV v2.0.0（versionCode 10703）", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Text(text = "包名：com.oriontv", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Text(text = "Kotlin + Compose for TV + Media3 原生重写。", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
    }
}
