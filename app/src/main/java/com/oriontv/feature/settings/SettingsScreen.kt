package com.oriontv.feature.settings

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.BuildConfig
import com.oriontv.core.focus.onKeyUp
import com.oriontv.feature.remoteinput.QrCodeGenerator
import com.oriontv.feature.remoteinput.RemoteInputLogic
import com.oriontv.feature.remoteinput.RemoteInputModal
import com.oriontv.feature.update.UpdateViewModel
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.orionFocus
import com.oriontv.core.focus.preferredFocus
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 设置左菜单（ui-design.md §5.7）：源地址 / 播放设置 / 远程输入 / 检查更新 / 关于。 */
private val SettingsMenu = listOf("源地址", "播放设置", "远程输入", "检查更新", "关于")

/**
 * 设置页（ui-design.md §5.7）：左右两栏。
 * 左菜单列表，右内容区随左菜单切换；焦点在左菜单，右键进右栏，左键回左菜单
 *（手工焦点机：只响应松键 KeyUp，菜单上下顺序移动由空间导航处理，
 * 左右切换由 FocusRequester 显式交接，焦点永不丢失）；
 * 源地址：API + M3U 两行输入，保存立即生效并清除登录态（保存按钮未改时不可点）；
 * 播放设置：下集预告、跳过片头、倍速记忆、无痕模式、后台播放与画中画开关 + 片头时长；
 * 远程输入：开关 + 服务状态 + 二维码 + 配对码；检查更新：当前版本 + 检查按钮 + 更新卡片。
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    // P1-6：由 NavHost 传入同一个 Activity 级实例，不再自己建第二个
    //（冷启动检查与手动检查共用一份更新状态）。
    updateViewModel: UpdateViewModel,
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteState by viewModel.remoteInputState.collectAsState()
    val updateState by updateViewModel.uiState.collectAsState()
    // C5-P2-5：selectedMenu 原来是 remember——进程重建/切走再回后回到 0，
    // 与焦点恢复（focusRestoreTick 用 menuRequesters[selectedMenu]）对不上；
    // 改 rememberSaveable，首进焦点也落 menuRequesters[selectedMenu]。
    var selectedMenu by rememberSaveable { mutableStateOf(0) }
    // C6-P2-4：selectedMenu 经 rememberSaveable 恢复后未钳制——将来菜单缩减
    // （如某项下线），恢复出的旧下标越界，menuRequesters[selectedMenu] 直接
    // 崩溃；读侧一律走钳制后的 menuIndex，写侧（onClick）只产生合法下标。
    val menuIndex = selectedMenu.coerceIn(0, (SettingsMenu.size - 1).coerceAtLeast(0))
    var showQrModal by remember { mutableStateOf(false) }
    val menuRequesters = remember { List(SettingsMenu.size) { FocusRequester() } }
    val rightRequester = remember { FocusRequester() }
    val qrTriggerRequester = remember { FocusRequester() }
    // 二维码弹窗关闭后焦点还给触发点（P1-5）。
    var qrModalWasOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        menuRequesters[menuIndex].requestFocus()
        viewModel.restoreRemoteInputIfEnabled()
    }
    // C4-P1-2：NavHost 级弹窗关闭后焦点回左菜单当前项（不重跑远程输入恢复）。
    LaunchedEffect(focusRestoreTick) {
        if (focusRestoreTick > 0) menuRequesters[menuIndex].requestFocus()
    }
    // Toast 2 秒消失（ui-design.md §5.8）。
    // B5-P2-11：以事件 id 为 key——同文案连续两次时 id 不同，effect 一定重跑。
    LaunchedEffect(uiState.toast?.id) {
        if (uiState.toast != null) {
            delay(2000)
            viewModel.consumeToast()
        }
    }
    LaunchedEffect(updateState.toast?.id) {
        if (updateState.toast != null) {
            delay(2000)
            updateViewModel.consumeToast()
        }
    }
    // P1-5：二维码弹窗关闭后焦点还给“放大二维码”触发按钮。
    LaunchedEffect(showQrModal) {
        if (showQrModal) {
            qrModalWasOpen = true
        } else if (qrModalWasOpen) {
            qrModalWasOpen = false
            qrTriggerRequester.requestFocus()
        }
    }

    // 第 7 轮 P2-2：Back 消费层级理清——
    // ① 全局更新卡片的关闭只归 NavHost 的 BackHandler（OrionNavHost，
    // enabled=showUpdateCard）：这里曾有一份同样的分支，焦点在弹窗外时
    // 它比 NavHost 的后注册、反而先触发，两处做同一件事，删掉本份；
    // ② 本页自己的 BackHandler 只留二维码弹窗（RemoteInputModal 自己的
    // BackHandler 是第一道——焦点在弹窗内时它先触发；焦点不在弹窗内
    // （如焦点丢失）时这道兜底）；
    // ③ 下面外层 Box 的 onKeyUp 只在焦点落在设置内容区时触发（弹窗焦点
    // 陷阱正常时到不了这里），按 settingsBackAction 决策：卡片/二维码
    // 开着先关弹窗，不直接 onBack() 退出设置页（旧实现恒 onBack()，
    // 焦点一旦以某种方式回到内容区而卡片仍开着，Back 会直接退出设置页）。
    // 三层互补不重叠：弹窗内→弹窗自己的 handler；弹窗外无焦点→本页/
    // NavHost 的 handler；焦点在内容区→Box onKeyUp。
    BackHandler(enabled = showQrModal) {
        showQrModal = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onKeyUp { event ->
                if (event.key == Key.Back) {
                    when (settingsBackAction(updateState.showUpdateCard, showQrModal)) {
                        SettingsBackAction.DismissUpdateCard -> {
                            updateViewModel.dismissCard()
                            true
                        }
                        SettingsBackAction.CloseQrModal -> {
                            showQrModal = false
                            true
                        }
                        SettingsBackAction.ExitSettings -> {
                            onBack()
                            true
                        }
                    }
                } else {
                    false
                }
            },
    ) {
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
                        selected = menuIndex == index,
                        modifier = Modifier.focusRequester(menuRequesters[index]),
                        onClick = { selectedMenu = index },
                        onRight = { rightRequester.requestFocus() },
                    )
                }
            }

            // 右：内容区。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 48.dp)
                    .onKeyUp { event ->
                        if (event.key == Key.DirectionLeft) {
                            menuRequesters[menuIndex].requestFocus()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                when (menuIndex) {
                    0 -> SourceSettings(
                        apiUrl = uiState.apiUrl,
                        onApiUrlChange = { viewModel.onApiUrlChange(it) },
                        m3uUrl = uiState.m3uUrl,
                        onM3uUrlChange = { viewModel.onM3uUrlChange(it) },
                        dirty = viewModel.isDirty,
                        isSaving = uiState.isSaving,
                        isLoading = uiState.isLoading,
                        onSave = { viewModel.saveSourceAddresses() },
                        firstRequester = rightRequester,
                    )
                    1 -> PlaybackSettings(
                        state = uiState,
                        onNextPreviewChange = { viewModel.setNextEpisodeOverlay(it) },
                        onSkipIntroChange = { viewModel.setAutoSkipIntro(it) },
                        onSpeedMemoryChange = { viewModel.setSpeedMemory(it) },
                        onIncognitoChange = { viewModel.setIncognitoMode(it) },
                        onBackgroundChange = { viewModel.setBackgroundPlayback(it) },
                        onCycleIntroSeconds = { viewModel.cycleIntroSkipSeconds() },
                        firstRequester = rightRequester,
                    )
                    2 -> RemoteInputSettings(
                        enabled = uiState.remoteInputEnabled,
                        isRunning = remoteState.isRunning,
                        serverUrl = remoteState.serverUrl,
                        pairingCode = remoteState.pairingCode,
                        pairingCodeRemainingSeconds = remoteState.pairingCodeRemainingSeconds,
                        error = remoteState.error,
                        onToggle = { viewModel.setRemoteInputEnabled(it) },
                        onShowQr = { showQrModal = true },
                        firstRequester = rightRequester,
                        qrTriggerRequester = qrTriggerRequester,
                    )
                    3 -> UpdateSettings(
                        currentVersion = updateState.currentVersion,
                        isChecking = updateState.isChecking,
                        onCheckUpdate = { updateViewModel.checkForUpdate(silent = false) },
                        firstRequester = rightRequester,
                    )
                    4 -> AboutSettings(firstRequester = rightRequester)
                }
            }
        }

        // B6-P2-9：加载中给明确 UI——旧实现 isLoading 无任何 UI，加载失败时
        // 页面一片空白、用户不知道在等什么。
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "正在加载设置…",
                    style = OrionTextStyles.Body,
                    color = OrionColors.TextSecondary,
                )
            }
        }

        // Toast：底部居中，2 秒消失。
        val toast = uiState.toast ?: updateState.toast
        if (toast != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                OrionToast(message = toast.message, modifier = Modifier.padding(bottom = 48.dp))
            }
        }
        // 更新失败红字：除卡片内已显示外，这里也给一行（卡片关闭后仍可见）。
        if (updateState.error != null && !updateState.showUpdateCard) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = updateState.error ?: "",
                    style = OrionTextStyles.Body,
                    color = OrionColors.Error,
                    modifier = Modifier.padding(bottom = 96.dp),
                )
            }
        }
        if (uiState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    text = uiState.error ?: "",
                    style = OrionTextStyles.Body,
                    color = OrionColors.Error,
                    modifier = Modifier.padding(bottom = 96.dp),
                )
            }
        }
    }

    // 更新卡片只由 NavHost 渲染（C4-P2-1：这里曾与 NavHost 用同一条件渲染
    // 同一张卡，两卡精确重叠；关闭后焦点由 NavHost 的 focusRestoreTick 恢复）。

    // 远程输入二维码弹窗。
    if (showQrModal) {
        RemoteInputModal(
            serverUrl = remoteState.serverUrl,
            pairingCode = remoteState.pairingCode,
            pairingCodeRemainingSeconds = remoteState.pairingCodeRemainingSeconds,
            error = remoteState.error,
            onDismiss = { showQrModal = false },
            // P2-14：服务未启动时弹窗内一键开启。
            onEnableRemoteInput = { viewModel.setRemoteInputEnabled(true) },
        )
    }
}

@Composable
private fun SettingsMenuItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onRight: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .onKeyUp {
                when (it.key) {
                    Key.DirectionCenter, Key.Enter -> {
                        onClick()
                        true
                    }
                    Key.DirectionRight -> {
                        onRight()
                        true
                    }
                    else -> false
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
    dirty: Boolean,
    isSaving: Boolean,
    // C8-P2-5：加载完成前输入仍可编辑、保存按钮极窄窗口可用——加载中禁用保存。
    isLoading: Boolean,
    onSave: () -> Unit,
    firstRequester: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "源地址", style = OrionTextStyles.SectionTitle)
        SettingsInputRow(
            label = "API 地址",
            value = apiUrl,
            onValueChange = onApiUrlChange,
            focusRequester = firstRequester,
        )
        SettingsInputRow(label = "M3U 地址", value = m3uUrl, onValueChange = onM3uUrlChange)
        PrimaryButton(
            text = if (isSaving) "保存中…" else "保存",
            onClick = onSave,
            enabled = dirty && !isSaving && !isLoading,
        )
        Text(text = "保存后立即生效，并清除登录状态。", style = OrionTextStyles.Small)
    }
}

@Composable
private fun SettingsInputRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // P2-1（第 5 轮）：输入框内部用 TextFieldValue，才能知道光标位置。
    // 左键行为：光标不在行首时只移光标（消费掉松键，不冒泡到父级回菜单）；
    // 光标已在行首时放行，冒泡到父级回左菜单。光标移动发生在 KeyDown，
    // 这里只拦 KeyUp；是否消费按 KeyDown 时快照的移动前位置判断
    //（C7-P2-3：KeyUp 时读到的已是移动后的位置，第 1 位按左键会误判）。
    var textFieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // C7-P2-3：按左键前光标位置的快照——光标移动发生在 KeyDown，KeyUp 时
    // 读到的已是移动后的位置（第 1 位按左键会读到 0、误放行回菜单）；
    // 在预览阶段 KeyDown 里快照（不消费，不影响光标移动本身），KeyUp 时
    // 按快照判断是否消费。直接在 KeyDown 里消费是错的：会拦住光标移动。
    var cursorBeforeLeftMove by remember { mutableStateOf(-1) }
    LaunchedEffect(value) {
        if (value != textFieldValue.text) {
            textFieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }
    Column {
        Text(text = label, style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OrionDimens.CardCornerRadius))
                .background(OrionColors.Surface)
                .border(
                    width = if (focused) OrionDimens.FocusBorderWidth else 1.dp,
                    color = if (focused) OrionColors.Brand else OrionColors.Divider,
                    shape = RoundedCornerShape(OrionDimens.CardCornerRadius),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = textFieldValue,
                onValueChange = {
                    textFieldValue = it
                    onValueChange(it.text)
                },
                textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        // C7-P2-3：隧道阶段先于输入框自己的光标移动，读到的是
                        // 移动前位置；只快照、不消费。
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionLeft) {
                            cursorBeforeLeftMove = textFieldValue.selection.start
                        }
                        false
                    }
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && event.key == Key.DirectionLeft) {
                            // C7-P2-3：按移动前光标位置判断——不在行首时消费松键，
                            // 只移光标，不回菜单；已在行首时放行，冒泡到父级回左菜单。
                            shouldConsumeSettingsLeftKeyUp(cursorBeforeLeftMove)
                        } else {
                            false
                        }
                    },
                decorationBox = { innerTextField ->
                    if (textFieldValue.text.isEmpty()) {
                        Text(text = "请输入$label", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                    }
                    innerTextField()
                },
            )
        }
    }
}

/** 设置输入框左键松键是否消费（可单测，C7-P2-3）：按移动前光标位置判断——
 * 不在行首（>0）时消费，只移光标、不回左菜单；已在行首时放行回菜单。
 * 注意入参必须是 KeyDown 时快照的移动前位置，KeyUp 时读到的已是移动后位置。 */
internal fun shouldConsumeSettingsLeftKeyUp(cursorBeforeMove: Int): Boolean =
    cursorBeforeMove > 0

/** 播放设置：下集预告、跳过片头、倍速记忆、无痕模式、后台播放与画中画 + 片头时长。 */
@Composable
private fun PlaybackSettings(
    state: SettingsUiState,
    onNextPreviewChange: (Boolean) -> Unit,
    onSkipIntroChange: (Boolean) -> Unit,
    onSpeedMemoryChange: (Boolean) -> Unit,
    onIncognitoChange: (Boolean) -> Unit,
    onBackgroundChange: (Boolean) -> Unit,
    onCycleIntroSeconds: () -> Unit,
    firstRequester: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "播放设置", style = OrionTextStyles.SectionTitle)
        ToggleRow(
            label = "下一集预告",
            description = "播到片尾附近时弹出下一集预告，可立即播放或关闭",
            checked = state.nextEpisodeOverlay,
            onToggle = { onNextPreviewChange(!state.nextEpisodeOverlay) },
            modifier = Modifier.focusRequester(firstRequester),
        )
        ToggleRow(
            label = "跳过片头",
            description = "播放到片头结束处自动跳过；没标过片头时间时按下面“片头时长”跳",
            checked = state.autoSkipIntro,
            onToggle = { onSkipIntroChange(!state.autoSkipIntro) },
        )
        ToggleRow(
            label = "倍速记忆",
            description = "记住每部影片的倍速，下次开播自动恢复；关掉后一律 1 倍速",
            checked = state.speedMemory,
            onToggle = { onSpeedMemoryChange(!state.speedMemory) },
        )
        ToggleRow(
            label = "无痕模式",
            description = "开启后不保存播放记录与断点，不写搜索历史；收藏不受影响",
            checked = state.incognitoMode,
            onToggle = { onIncognitoChange(!state.incognitoMode) },
        )
        ToggleRow(
            label = "后台播放与画中画",
            description = "切到后台时继续播放并进入画中画；关掉后退出播放页即停播",
            checked = state.backgroundPlayback,
            onToggle = { onBackgroundChange(!state.backgroundPlayback) },
        )
        // 片头时长：点按循环切换档位（0=不跳，只用手动标记的片头时间）。
        // P2-20：“跳过片头”关了就置灰且不可切换（值存着但不生效，别让人误操作）。
        val introEnabled = state.autoSkipIntro
        var focused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusable(introEnabled)
                .onFocusChanged { focused = it.isFocused }
                .onKeyUp {
                    if (!introEnabled) {
                        false
                    } else if (it.key == Key.DirectionCenter || it.key == Key.Enter) {
                        onCycleIntroSeconds()
                        true
                    } else {
                        false
                    }
                }
                .orionFocus(focused)
                .alpha(if (introEnabled) 1f else 0.4f)
                .background(OrionColors.Surface, RoundedCornerShape(OrionDimens.CardCornerRadius))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "片头时长", style = OrionTextStyles.Body)
                Text(
                    text = "没标过片头时间时，按这个时长跳过片头",
                    style = OrionTextStyles.Small,
                    color = OrionColors.TextSecondary,
                )
            }
            Text(
                text = if (state.introSkipSeconds == 0) "不跳过" else "${state.introSkipSeconds}秒",
                style = OrionTextStyles.Body,
                color = OrionColors.Brand,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
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
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = OrionTextStyles.Body)
            Text(text = description, style = OrionTextStyles.Small, color = OrionColors.TextSecondary)
        }
        Text(
            text = if (checked) "开" else "关",
            style = OrionTextStyles.Body,
            color = if (checked) OrionColors.Brand else OrionColors.TextSecondary,
        )
    }
}

/** 远程输入：开关 + 服务状态（异常红字）+ 二维码 + 配对码。 */
@Composable
private fun RemoteInputSettings(
    enabled: Boolean,
    isRunning: Boolean,
    serverUrl: String?,
    pairingCode: String?,
    pairingCodeRemainingSeconds: Int?,
    error: String?,
    onToggle: (Boolean) -> Unit,
    onShowQr: () -> Unit,
    firstRequester: FocusRequester,
    qrTriggerRequester: FocusRequester,
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(serverUrl) {
        qrBitmap = if (serverUrl.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.Default) { QrCodeGenerator.generateBitmap(serverUrl) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "远程输入", style = OrionTextStyles.SectionTitle)
        ToggleRow(
            label = "远程输入",
            description = "开启后手机扫码，在手机上打字，实时同步到电视搜索框",
            checked = enabled,
            onToggle = { onToggle(!enabled) },
            modifier = Modifier.focusRequester(firstRequester),
        )
        // P1-10：明文服务提示（局域网内可被嗅探）。
        Text(
            text = "注意：这是局域网明文服务，同一网络下的设备可以看到传输内容；配对码 5 分钟内有效，请勿在公共网络使用。",
            style = OrionTextStyles.Small,
            color = OrionColors.TextSecondary,
        )
        if (enabled) {
            when {
                isRunning -> {
                    Text(
                        text = "服务运行中：$serverUrl",
                        style = OrionTextStyles.Body,
                        color = OrionColors.PlayingGreen,
                    )
                    if (!pairingCode.isNullOrBlank()) {
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
                    if (qrBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "远程输入二维码",
                            modifier = Modifier
                                .width(200.dp)
                                .height(200.dp)
                                .clip(RoundedCornerShape(OrionDimens.PanelCornerRadius))
                                .background(androidx.compose.ui.graphics.Color.White)
                                .padding(8.dp),
                        )
                    }
                    SecondaryButton(
                        text = "放大二维码",
                        onClick = onShowQr,
                        modifier = Modifier.preferredFocus(qrTriggerRequester),
                    )
                }
                error != null -> {
                    Text(text = error, style = OrionTextStyles.Body, color = OrionColors.Error)
                }
                else -> {
                    Text(text = "服务启动中…", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
                }
            }
        } else {
            Text(
                text = "用手机扫码，在手机上输入文字，实时同步到电视。服务端口：12346。",
                style = OrionTextStyles.Body,
                color = OrionColors.TextSecondary,
            )
        }
    }
}

/** 检查更新：当前版本 + “检查更新”按钮；有新版弹更新卡片。 */
// B4-P2-22：更新错误只留底部常驻那处（卡片关闭后仍可见），这里的内联
// error Text 与底部重复显示，删掉（error 参数随之去掉）。
@Composable
private fun UpdateSettings(
    currentVersion: String,
    isChecking: Boolean,
    onCheckUpdate: () -> Unit,
    firstRequester: FocusRequester,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text = "检查更新", style = OrionTextStyles.SectionTitle)
        Text(text = "当前版本：v$currentVersion", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        SecondaryButton(
            text = if (isChecking) "检查中…" else "检查更新",
            onClick = onCheckUpdate,
            enabled = !isChecking,
            modifier = Modifier.focusRequester(firstRequester),
        )
    }
}

/** 关于：版本与包名。 */
@Composable
private fun AboutSettings(firstRequester: FocusRequester) {
    // 关于页无可交互控件：整块内容可聚焦，给右键进右栏一个落点（焦点永不丢失）。
    var focused by remember { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .focusable()
            .focusRequester(firstRequester)
            .onFocusChanged { focused = it.isFocused }
            .orionFocus(focused)
            .padding(8.dp),
    ) {
        Text(text = "关于", style = OrionTextStyles.SectionTitle)
        Text(
            // P2-19：debug 包的 VERSION_NAME 带 "-debug" 后缀，显示时去掉。
            text = "OrionTV v${BuildConfig.VERSION_NAME.substringBefore("-")}（versionCode ${BuildConfig.VERSION_CODE}）",
            style = OrionTextStyles.Body,
            color = OrionColors.TextSecondary,
        )
        Text(text = "包名：com.oriontv", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
        Text(text = "Kotlin + Compose for TV + Media3 原生重写。", style = OrionTextStyles.Body, color = OrionColors.TextSecondary)
    }
}

/** 设置页返回键动作（第 7 轮 P2-2，纯决策，可单测）。 */
internal enum class SettingsBackAction {
    /** 全局更新卡片开着：先关卡片，不退出设置页。 */
    DismissUpdateCard,

    /** 二维码弹窗开着：先关弹窗，不退出设置页。 */
    CloseQrModal,

    /** 无弹窗：退出设置页。 */
    ExitSettings,
}

/**
 * 第 7 轮 P2-2：设置页焦点在内容区时返回键的决策——弹窗在上先关弹窗。
 * 全局更新卡片优先于本页二维码弹窗（卡片是 NavHost 级全局弹窗；
 * 两者正常互斥，同时开着时先关卡片）。
 */
internal fun settingsBackAction(showUpdateCard: Boolean, showQrModal: Boolean): SettingsBackAction =
    when {
        showUpdateCard -> SettingsBackAction.DismissUpdateCard
        showQrModal -> SettingsBackAction.CloseQrModal
        else -> SettingsBackAction.ExitSettings
    }
