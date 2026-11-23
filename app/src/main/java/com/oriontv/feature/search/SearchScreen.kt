package com.oriontv.feature.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Text
import com.oriontv.core.focus.preferredFocus
import com.oriontv.core.focus.requestFocusWithRetry
import com.oriontv.feature.remoteinput.RemoteInputModal
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.components.CategoryChip
import com.oriontv.ui.components.OrionToast
import com.oriontv.ui.components.PrimaryButton
import com.oriontv.ui.components.SecondaryButton
import com.oriontv.ui.components.VideoCard
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles
import kotlinx.coroutines.delay

/**
 * 搜索页（ui-design.md §5.5）：
 * 顶部搜索框（屏上键盘 + 扫码远程输入入口）+ 输入时下方联想 chips；
 * 结果网格；无结果提示 + “换个关键词”；搜索历史 chips（常驻不跳、标题旁“清除”一键清空）；
 * 搜索不清空已输内容，返回搜索页保留上次结果。
 * 焦点（P1-4）：首进落搜索框；从详情返回时恢复上次点的结果卡片，不拉回搜索框。
 */
@Composable
fun SearchScreen(
    onNavigate: (OrionRoute) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    /** C4-P1-2：NavHost 级弹窗关闭后 +1，各页重跑默认焦点恢复。 */
    focusRestoreTick: Int = 0,
    /**
     * C11-P2-1：全局弹窗（登录/更新卡片）是否正开着——开着时各焦点 effect
     * 不抢焦点（与详情/首页/播放页的 globalModalOpen 门控同款）。
     */
    globalModalOpen: Boolean = false,
    /**
     * Jewel 第 9 轮 P2-4：左侧导航轨是否正持有焦点——轨获焦时结果区 latch
     * 清掉，下一次搜索启动不再把焦点从轨上拉回搜索框（与键盘容器同款）。
     */
    navRailHasFocus: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsState()
    val remoteState by viewModel.remoteInputState.collectAsState()
    val searchRequester = remember { FocusRequester() }
    // 第 73 轮：预览阶段焦点移动（onPreviewKeyEvent）与系统输入法收起用。
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scanButtonRequester = remember { FocusRequester() }
    // C8-P1-3/A8-P2-2：错误态按钮的焦点锚点（“重试”之前根本没有 requester）。
    val retryRequester = remember { FocusRequester() }
    val changeKeywordRequester = remember { FocusRequester() }
    val resultRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    // C6-P1-1：搜索框获焦态（焦点描边用；首进默认落点却无任何焦点态时，
    // 用户分不清焦点在搜索框还是在旁边的按钮上）。
    var searchBoxFocused by remember { mutableStateOf(false) }
    // C10-P2-1：输入框内部用 TextFieldValue，才能知道光标位置（与设置输入框
    // 同款，见 SettingsInputRow 的 C7-P2-3）：光标不在边界时左右键只移光标；
    // 光标已在行首/行尾时移焦点——左出到导航轨、右进“屏上键盘/手机扫码输入”
    // 按钮，是有意逃逸，不是陷阱破口。
    // 第 73 轮：焦点移动改到 onPreviewKeyEvent（KeyDown 预览阶段）——
    // BasicTextField 在 KeyDown 就吃掉方向键做光标移动，KeyUp 阶段再处理
    // 焦点已无从移动（第 72 轮实测：搜索框里按 下/右 焦点出不去）。
    // 旧的 KeyUp 快照法（cursorBeforeLeftMove/cursorBeforeRightMove）随之删除。
    var searchTextFieldValue by remember { mutableStateOf(TextFieldValue(uiState.query)) }
    // 外部改了 query（历史填回、清空、屏上键盘输入）时同步回输入框，光标落行尾。
    LaunchedEffect(uiState.query) {
        if (uiState.query != searchTextFieldValue.text) {
            searchTextFieldValue = TextFieldValue(uiState.query, TextRange(uiState.query.length))
        }
    }
    // Jewel 第 8 轮 P1-1：加载开始即抢焦点时，必须区分焦点在哪——
    // 焦点在屏上键盘按键上时绝不能收回搜索框（遥控逐字输入时每敲一字、
    // 300ms 防抖一到就被偷走，键盘基本不可用）；焦点在结果区时才收回
    // （C8-P1-3 原意图：远程输入触发新搜索、结果网格卸载后焦点有明确落点）。
    // 不能在 effect 里现读结果区焦点态：加载开始的同时结果网格即卸载，
    // 焦点随卸载节点一起丢失，现读到的已是 false、该收回的场景反而收不回；
    // 故用 latch——进入结果区时置位，落到搜索框/键盘时才清掉。
    var keyboardHasFocus by remember { mutableStateOf(false) }
    var resultsAreaHadFocus by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var showRemoteInputModal by remember { mutableStateOf(false) }
    // Jewel 第 9 轮 P2-1：活的门控读数（与详情/首页同款）——tick/error effect
    // 块捕获的 globalModalOpen/showRemoteInputModal 是启动时快照，弹窗在重试
    // 窗口内打开时旧块读不到；State 随重组更新，重试每次复核都读最新值。
    // 注意：必须放在 showRemoteInputModal 声明之后（Kotlin 局部变量先声明后引用）。
    val modalOpenState = rememberUpdatedState(globalModalOpen)
    val remoteModalOpenState = rememberUpdatedState(showRemoteInputModal)
    // P2-10（第 5 轮）：键盘开关用 rememberSaveable——进详情返回后键盘
    // 状态不丢（普通 remember 随组合处置而丢失）。
    var showKeyboard by rememberSaveable { mutableStateOf(false) }
    // 第 81 轮 A11：键盘档位（全键/九键、中/英文）由搜索页持有——键盘开着
    // 时才在组合里，档位放键盘函数里 remember 会随开关重建丢回默认全键；
    // 放这里则开关不丢档位（与上面 showKeyboard 的 rememberSaveable 同款意图）。
    var keyboardNineKeyMode by remember { mutableStateOf(false) }
    var keyboardZhMode by remember { mutableStateOf(true) }
    // 扫码弹窗关闭后焦点还给触发点（P1-5）。
    var modalWasOpen by remember { mutableStateOf(false) }

    // Jewel 第 9 轮 P2-4：导航轨获焦即清结果区 latch——焦点已移到轨上时，
    // 下一次搜索启动（远程输入文本到达/防抖到点）不再把焦点从轨上拉回搜索框。
    // 轨内移动不触发（NavRail 只在整轨 hasFocus 翻转时回调）；焦点轨→结果区
    // 时结果区容器自己的 onFocusChanged 会重新置位 latch，不丢 P1-1 的收回语义。
    LaunchedEffect(navRailHasFocus) {
        if (navRailHasFocus) resultsAreaHadFocus = false
    }

    // C5-P1-1：屏上键盘由开变关时焦点回搜索框——之前键盘关闭后焦点留在已
    // 卸载的键盘按键上，遥控器数秒内按什么都没反应。
    // C11-P2-1：全局弹窗开着时不抢焦（弹窗关闭后由 tick effect 恢复）。
    var keyboardWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showKeyboard) {
        if (showKeyboard) {
            keyboardWasOpen = true
            // 第 73 轮：应用内键盘打开时收起系统输入法——两键盘同时开会抢空间，
            // 系统输入法还会把结果区再压一截。输入框焦点不受影响。
            keyboardController?.hide()
        } else if (keyboardWasOpen) {
            keyboardWasOpen = false
            // Jewel 第 8 轮 P1-1：键盘卸载时键盘焦点态不再回调，手动清掉，
            // 不留陈旧的 keyboardHasFocus 拦住后续的加载抢焦。
            keyboardHasFocus = false
            // C11-P2-1：全局弹窗开着时不抢焦（弹窗关闭后由 tick effect 恢复）。
            if (!globalModalOpen) searchRequester.requestFocus()
        }
    }

    // P1-4/P2-9：首进落搜索框；返回时按条目键恢复上次的结果卡片。
    // 结果可能仍在加载（返回时搜索在途），最多等 1 秒；等不到或键已不在
    // 结果里，回落搜索框。
    // C4-P1-2：弹窗关闭后 tick 变化也重跑同一条恢复路。
    // C11-P2-1：全局弹窗开着时跳过本次抢焦，不置 initialFocusDone——
    // 弹窗关闭后 tick +1 重跑本 effect 再落焦。key 加 globalModalOpen：
    // 本 effect 有最长 1 秒的等待循环，弹窗在循环中途打开时也要取消抢焦。
    // C12-P1-5：扫码弹窗开着时同样不抢——全局弹窗关闭的 tick 会重跑本
    // effect，焦点会被抢出扫码弹窗；扫码弹窗关闭后由它自己的关闭 effect
    // 把焦点还给触发点。
    // Jewel 第 8 轮 P2-4：无保存键时不轮询——findSavedResult(null) 恒 -1，
    // 空转 10×100ms 才回落，白白拖慢弹窗关闭后的焦点恢复 1 秒。
    LaunchedEffect(focusRestoreTick, globalModalOpen) {
        if (!shouldRunSearchTickRestore(globalModalOpen, showRemoteInputModal)) return@LaunchedEffect
        if (!viewModel.initialFocusDone) {
            searchRequester.requestFocus()
            viewModel.initialFocusDone = true
        } else if (!shouldPollSavedResult(viewModel.savedResultKey)) {
            searchRequester.requestFocus()
        } else {
            // P2-9：按条目键恢复；返回时结果可能仍在加载，最多等 1 秒。
            val key = viewModel.savedResultKey
            var target = -1
            for (attempt in 0 until 10) {
                target = findSavedResult(uiState.results, key)
                if (target >= 0) break
                delay(100)
            }
            if (target >= 0) {
                gridState.scrollToItem(target)
                // C4-P2-2/B4-P2-23：目标卡片尚未组合完成时重试请求，仍失败才回搜索框，
                // 不再靠 120ms 固定延时猜时序、也不再静默丢焦点。
                val restored = resultRequesters[target]?.requestFocusWithRetry(
                    isStillValid = { shouldRunSearchTickRestore(modalOpenState.value, remoteModalOpenState.value) },
                ) ?: false
                if (!restored && shouldRunSearchTickRestore(modalOpenState.value, remoteModalOpenState.value)) searchRequester.requestFocus()
            } else {
                // B51-P2-1：与目标找到时的回落分支同款门控——轮询窗口内扫码
                // 弹窗打开时不把焦点从弹窗偷回搜索框。
                if (shouldRunSearchTickRestore(modalOpenState.value, remoteModalOpenState.value)) searchRequester.requestFocus()
            }
        }
    }

    // C8-P1-3：远程输入触发新搜索时结果网格卸载，焦点若在结果卡上即丢失、
    // 无任何恢复——加载一开始就把焦点收回搜索框（网格卸载后焦点有明确落点）；
    // 搜索失败时落到错误态按钮（有“重试”落“重试”，无结果落“换个关键词”，
    // 都落不上才回搜索框）。
    // C11-P2-1：key 加 globalModalOpen——弹窗开着时跳过抢焦，关闭后 effect
    // 重跑、按当前态（仍在加载落搜索框 / 已失败落错误态按钮）补落焦点。
    // C13-P2-1：加扫码弹窗门控——C12-P1-5 只给 tick effect 加了，加载/错误
    // 两个 effect 只有全局弹窗门控；扫码弹窗开着时搜索加载/失败会把焦点抢出
    // 弹窗。恢复口：扫码弹窗关闭后由它自己的关闭 effect 把焦点还给触发点。
    // Jewel 第 8 轮 P1-1：旧实现无守卫——屏上键盘每敲一字、300ms 防抖一到、
    // 搜索一开始就把焦点从键盘按键上偷回搜索框，遥控逐字输入基本不可用。
    // 改为只在焦点确实在结果区时才收回（见 shouldReclaimSearchFocusOnLoading）：
    // 焦点在键盘按键上时不动；扫码弹窗开着时不动（焦点在弹窗内）。
    // 结果区 latch 的道理见上面 resultsAreaHadFocus 的注释。
    // 两道门控取合取：C13-P2-1 的全局/扫码弹窗门控与 P1-1 的结果区 latch
    // 门控都要过才收回，任一不满足都不抢焦。
    // C65-P2-1：key 加 searchStartTick——在途再搜（isLoading 已是 true）
    // 时 searchNow 只清结果、不翻转 isLoading，(true, globalModalOpen)
    // 不变 effect 根本不重跑，焦点随卸载的联想 chip/结果卡一起丢失、
    // 全页无其他兜底。searchStartTick 每次 searchNow 都 +1，key 变化即
    // 重跑回收；无关重组不触发（tick 只在 searchNow 变）；isLoading
    // 为 false 时守卫直接拦住，不抢焦。
    LaunchedEffect(uiState.isLoading, globalModalOpen, uiState.searchStartTick) {
        if (uiState.isLoading && shouldRunSearchTickRestore(globalModalOpen, showRemoteInputModal) &&
            shouldReclaimSearchFocusOnLoading(
                resultsAreaHadFocus = resultsAreaHadFocus,
                keyboardHasFocus = keyboardHasFocus,
                remoteInputModalOpen = showRemoteInputModal,
            )
        ) {
            searchRequester.requestFocus()
        }
    }
    LaunchedEffect(uiState.error, globalModalOpen) {
        if (!shouldRunSearchTickRestore(globalModalOpen, showRemoteInputModal)) return@LaunchedEffect
        val error = uiState.error ?: return@LaunchedEffect
        // Jewel 第 9 轮 P2-1：重试每次复核门控，弹窗在窗口内打开即停；
        // 耗尽后的回落搜索框也先复核，不在弹窗背后偷焦。
        val stillValid = { shouldRunSearchTickRestore(modalOpenState.value, remoteModalOpenState.value) }
        val landed = if (error is SearchError.Empty) {
            changeKeywordRequester.requestFocusWithRetry(isStillValid = stillValid)
        } else {
            retryRequester.requestFocusWithRetry(isStillValid = stillValid)
        }
        if (!landed && stillValid()) searchRequester.requestFocus()
    }

    // B6-P2-12：requester map 只增不减——结果数缩减时清掉越界下标的旧
    // FocusRequester，不让 map 随每次搜索无限长（下标只作组合内锚点，
    // 返回恢复走条目键，不冲突）。
    LaunchedEffect(uiState.results.size) {
        resultRequesters.keys.removeAll { it < 0 || it >= uiState.results.size }
    }

    // B6-P2-11：Toast 2 秒消失（ui-design.md §5.8，与播放页/设置页同款）。
    LaunchedEffect(uiState.toast?.id) {
        if (uiState.toast != null) {
            delay(2000)
            viewModel.consumeToast()
        }
    }

    // 配对成功后自动关弹窗（RN RemoteControlModal 的 onHandshake 同款）。
    LaunchedEffect(Unit) {
        viewModel.remoteInputHandshakes.collect { showRemoteInputModal = false }
    }
    LaunchedEffect(showRemoteInputModal) {
        if (showRemoteInputModal) {
            modalWasOpen = true
        } else if (modalWasOpen) {
            modalWasOpen = false
            // C11-P2-1：全局弹窗开着时不抢焦（弹窗关闭后由 tick effect 恢复）。
            if (!globalModalOpen) scanButtonRequester.requestFocus()
        }
    }

    // P2-10（第 5 轮）：返回键先收屏上键盘。注册在弹窗 handler 之前，
    // 两者都开时弹窗先关（后注册的优先）。
    // C12-P2-1：全局弹窗开着时本 handler 让位——后注册优先于 NavHost 的
    // 全局 handler，返回键会先收键盘、而不是先关视觉上在上的全局弹窗。
    BackHandler(enabled = shouldEnableKeyboardBackHandler(showKeyboard, globalModalOpen)) { showKeyboard = false }
    // P2-5（第 5 轮）：返回键先关扫码弹窗，不直接 pop 搜索页。
    // 顺序注意：弹窗在键盘之上，BackHandler 是后注册的优先；键盘的
    // handler（P2-10）注册在这一行之前，两者都开时先关弹窗。
    // C13-P1-2：加全局弹窗门控——同页键盘 handler、扫码弹窗自身、设置页兜底
    // 都有门控，唯独这处漏了；全局弹窗开着时返回键应先关全局弹窗。
    BackHandler(enabled = shouldEnableRemoteInputBackHandler(showRemoteInputModal, globalModalOpen)) { showRemoteInputModal = false }

    // B30-P2-1：根 Box + 扫码弹窗挂根级覆盖层（与设置页同款）——弹窗之前
    // 是根 Column 的流式子节点，fillMaxSize 占满剩余高度，键盘与结果区
    // 被量到 0 高、无声消失；移出 Column 后弹窗不再参与流式量测。
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // v2.0.23：键盘贴底通栏——根 Column 不再统一加左右/底部内边距；
            // 上半区（搜索框 + 结果）自己带 48dp 左右、32dp 顶部内边距
            // （与 ContentPadding 同值），键盘是根 Column 的末尾直系子项、
            // 全宽、贴着屏幕底边。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(
                        start = 48.dp,
                        end = 48.dp,
                        top = 32.dp,
                        bottom = if (showKeyboard) 16.dp else 32.dp,
                    ),
            ) {
                // 顶部搜索框 + 屏上键盘 + 扫码输入入口。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(OrionDimens.CapsuleRadius))
                            .background(OrionColors.Surface)
                            // C6-P1-1：获焦时 2dp 品牌色描边（设计稿焦点三件套：描边），
                            // 未获焦 1dp 分隔线——搜索框是首进默认落点，必须看得见焦点。
                            .border(
                                width = if (searchBoxFocused) 2.dp else 1.dp,
                                color = if (searchBoxFocused) OrionColors.Brand else OrionColors.Divider,
                                shape = RoundedCornerShape(OrionDimens.CapsuleRadius),
                            )
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        BasicTextField(
                            value = searchTextFieldValue,
                            onValueChange = {
                                searchTextFieldValue = it
                                viewModel.onQueryChange(it.text)
                            },
                            textStyle = OrionTextStyles.Body.copy(color = OrionColors.Text),
                            modifier = Modifier
                                .fillMaxWidth()
                                .preferredFocus(searchRequester)
                                .onFocusChanged {
                                    searchBoxFocused = it.isFocused
                                    // Jewel 第 8 轮 P1-1：焦点已回搜索框，结果区 latch 清掉，
                                    // 后续加载不再重复收回。
                                    if (it.isFocused) resultsAreaHadFocus = false
                                }
                                .onPreviewKeyEvent { event ->
                                    // 第 73 轮：焦点移动必须发生在 KeyDown 预览阶段——
                                    // BasicTextField 在 KeyDown 就吃掉方向键做光标移动，
                                    // 等 KeyUp 再处理时焦点移动已无从发生（第 72 轮实测：
                                    // 搜索框里按 下/右 焦点出不去，提示词也点不动）。
                                    // 判定见 shouldMoveSearchFocusOnKeyDown（可单测）。
                                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    if (
                                        shouldMoveSearchFocusOnKeyDown(
                                            key = event.key,
                                            cursorStart = searchTextFieldValue.selection.start,
                                            cursorEnd = searchTextFieldValue.selection.end,
                                            textLength = searchTextFieldValue.text.length,
                                        )
                                    ) {
                                        focusManager.moveFocus(
                                            when (event.key) {
                                                Key.DirectionLeft -> FocusDirection.Left
                                                Key.DirectionRight -> FocusDirection.Right
                                                Key.DirectionUp -> FocusDirection.Up
                                                else -> FocusDirection.Down
                                            },
                                        )
                                    } else {
                                        false
                                    }
                                },
                            decorationBox = { innerTextField ->
                                if (uiState.query.isEmpty()) {
                                    Text(text = "输入关键词搜索", style = OrionTextStyles.Body, color = OrionColors.TextWeak)
                                }
                                innerTextField()
                            },
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    // Jewel 第 9 轮 P2-4：顶栏按钮行获焦即清结果区 latch（与键盘容器
                    // 同款）——焦点已移到“屏上键盘/手机扫码输入”按钮上时，下一次搜索
                    // 启动不再把焦点从按钮上拉回搜索框。Row 整体 hasFocus 含子孙，
                    // 按钮间移动不重复触发；搜索框不在本 Row 内，不受影响。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.onFocusChanged { if (it.hasFocus) resultsAreaHadFocus = false },
                    ) {
                        SecondaryButton(
                            text = if (showKeyboard) "收起键盘" else "屏上键盘",
                            onClick = { showKeyboard = !showKeyboard },
                        )
                        SecondaryButton(
                            text = "手机扫码输入",
                            onClick = { showRemoteInputModal = true },
                            modifier = Modifier.preferredFocus(scanButtonRequester),
                        )
                    }
                }
                // v2.0.23：两栏布局取消——结果区占满全宽（联想/历史 chip + 结果
                // 网格都在上半区），键盘移到根 Column 末尾、贴底通栏（见下）。
                // 第 73 轮：联想 chip + 历史 chip 移到键盘上方（输入时内容，理应在
                // 输入框旁边）——原先在结果区顶部，键盘打开后结果区被压到 ~0，
                // chip 被顶出屏幕、点不到（第 72 轮遗留 1a/1b）。
                // 焦点 latch 与结果区同款：chip 获焦即置位，加载开始时 chip 卸载、
                // 焦点丢失，effect 凭 latch 把焦点收回搜索框。
                // 第 76 轮：两栏布局后 chip 在右栏顶部（与结果区同栏）。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { if (it.hasFocus) resultsAreaHadFocus = true },
                ) {
                    // 联想 chip（输入时出现，点按直接搜）。
                    if (uiState.suggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                            items(uiState.suggestions) { suggestion ->
                                CategoryChip(
                                    text = suggestion,
                                    selected = false,
                                    onClick = {
                                        showKeyboard = false
                                        viewModel.searchNow(suggestion)
                                    },
                                )
                            }
                        }
                    }

                    // 第 74 轮：搜索历史 chip 常驻——输字后也不卸载（原来 query 非空
                    // 即不渲染，历史一输字就消失、下方内容全往上跳）；每词旁的“删除”
                    // 去掉，标题旁一个“清除”按钮，一点清空全部。
                    if (uiState.history.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "搜索历史",
                                style = OrionTextStyles.SectionTitle,
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                text = "清除",
                                onClick = {
                                    viewModel.clearHistory()
                                    // 清空后整行被卸载，焦点回搜索框，不留在已卸载的按钮上。
                                    searchRequester.requestFocus()
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(OrionDimens.ChipSpacing)) {
                            items(uiState.history) { keyword ->
                                CategoryChip(
                                    text = keyword,
                                    selected = false,
                                    onClick = {
                                        showKeyboard = false
                                        viewModel.searchHistory(keyword)
                                        // C6-P1-3：点历史 chip 后搜新词，焦点回搜索框——
                                        // 搜索框常驻，搜完结果出来后方向键可下移进结果区。
                                        searchRequester.requestFocus()
                                    },
                                )
                            }
                        }
                    }
                }
                // P1-8：电视端屏上键盘（全键 + 9 键两档，最小可用实现）。
                // P2-10（第 5 轮）：搜完收键盘（“搜索”键、联想 chip、历史 chip
                // 都是“搜完”，都收起，不再盖住下方结果区）。
                // 第 76 轮：键盘已移到左栏（见上），这里不再重复渲染。

                // Jewel 第 8 轮 P1-1：结果区容器（结果/错误区）——
                // 焦点进结果区即置位 latch；加载开始时网格卸载、焦点随之丢失，
                // effect 凭 latch（而非卸载后的现读焦点态）决定收回搜索框。
                // 第 73 轮：联想 chip + 历史 chip 已移到键盘上方（本文件上部），
                // 不再属于结果区。
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onFocusChanged { if (it.hasFocus) resultsAreaHadFocus = true },
                ) {
                Spacer(modifier = Modifier.height(24.dp))
                when {
                    uiState.isLoading -> {
                        Text(
                            text = "正在搜索…",
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                        )
                    }
                    uiState.error != null -> {
                        val error = uiState.error
                        // 无结果提示 + “换个关键词”；其他错误也给分型文案 + 重试。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(text = error?.message ?: "", style = OrionTextStyles.Body)
                            Spacer(modifier = Modifier.height(24.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                PrimaryButton(
                                    text = "换个关键词",
                                    onClick = {
                                        viewModel.clearQuery()
                                        // C9-P1-2：error 置 null 后错误态 Column 卸载，焦点随
                                        // “换个关键词”按钮一起丢；回搜索框（与删历史 chip、
                                        // 点联想 chip 的现有做法一致）。
                                        searchRequester.requestFocus()
                                    },
                                    modifier = Modifier.preferredFocus(changeKeywordRequester),
                                )
                                if (error !is SearchError.Empty) {
                                    SecondaryButton(
                                        text = "重试",
                                        onClick = { viewModel.searchNow(uiState.searchedQuery) },
                                        modifier = Modifier.preferredFocus(retryRequester),
                                    )
                                }
                                if (error is SearchError.NotConfigured) {
                                    SecondaryButton(
                                        text = "去设置",
                                        onClick = { onNavigate(OrionRoute.Settings) },
                                    )
                                }
                            }
                        }
                    }
                    uiState.results.isNotEmpty() -> {
                        LazyVerticalGrid(
                            state = gridState,
                            // v2.0.23：结果区恒占满全宽（键盘贴底、不占左右），恒 6 列。
                            // （第 76 轮两栏 3 列已作废。）
                            columns = GridCells.Fixed(6),
                            horizontalArrangement = Arrangement.spacedBy(OrionDimens.CardSpacing),
                            verticalArrangement = Arrangement.spacedBy(OrionDimens.RowSpacing),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(uiState.results) { index, result ->
                                // B60-P2-1：FocusRequester() 带 @RememberInComposition，只能在
                                // remember 里创建；裸放在组合里每次重组都会新建（新版 lint 报
                                // error 阻断构建）。仍经 resultRequesters 共享表取，恢复逻辑不变。
                                val resultRequester = remember(index) {
                                    resultRequesters.getOrPut(index) { FocusRequester() }
                                }
                                VideoCard(
                                    title = result.title,
                                    posterUrl = result.poster,
                                    meta = listOfNotNull(
                                        result.year?.takeIf { it.isNotBlank() },
                                        result.source_name,
                                    ).joinToString(" · ").ifBlank { null },
                                    onClick = {
                                        onNavigate(
                                            OrionRoute.Detail(
                                                source = result.source,
                                                id = result.id.orEmpty(),
                                                title = result.title,
                                            ),
                                        )
                                    },
                                    onFocused = { viewModel.savedResultKey = searchResultKey(result) },
                                    modifier = Modifier.preferredFocus(resultRequester),
                                )
                            }
                        }
                    }
                    // P2-12：初始空白态给引导，不留空白内容区。
                    uiState.query.isBlank() && uiState.history.isEmpty() -> {
                        Text(
                            text = "输入关键词开始搜索，或点“手机扫码输入”用手机打字，也可以打开屏上键盘输入。",
                            style = OrionTextStyles.Body,
                            color = OrionColors.TextSecondary,
                        )
                    }
                }
                } // Jewel 第 8 轮 P1-1：结果区容器结束。
            } // v2.0.23：上半区（搜索框 + 结果）结束。
            // v2.0.23：键盘贴底通栏——根 Column 的末尾直系子项、全宽、
            // 底边与屏幕底边齐平；总高由键盘内容定（全键 312dp / 九键 376dp）。
            if (showKeyboard) {
                // Jewel 第 8 轮 P1-1：键盘容器焦点态（hasFocus 含子孙按键）——
                // 焦点进键盘即清掉结果区 latch，加载开始的 effect 不再偷焦点。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            keyboardHasFocus = it.hasFocus
                            if (it.hasFocus) resultsAreaHadFocus = false
                        },
                ) {
                    OnScreenKeyboard(
                        query = uiState.query,
                        onQueryChange = { viewModel.onQueryChange(it) },
                        // 第 74 轮：键盘传回完整关键词（含刚上屏的首选字），
                        // 直接用它搜，不再读可能还没更新的 uiState.query。
                        onSearch = { finalQuery ->
                            showKeyboard = false
                            viewModel.searchNow(finalQuery)
                        },
                        onDismiss = { showKeyboard = false },
                        nineKeyMode = keyboardNineKeyMode,
                        onNineKeyModeChange = { keyboardNineKeyMode = it },
                        zhMode = keyboardZhMode,
                        onZhModeChange = { keyboardZhMode = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // B30-P2-1：扫码弹窗挂根 Box 末尾（与设置页同款），不参与 Column
        // 流式量测——键盘开着时再开弹窗，键盘不再被压到 0 高。
        if (showRemoteInputModal) {
            RemoteInputModal(
                serverUrl = remoteState.serverUrl,
                pairingCode = remoteState.pairingCode,
                pairingCodeRemainingSeconds = remoteState.pairingCodeRemainingSeconds,
                error = remoteState.error,
                onDismiss = { showRemoteInputModal = false },
                // P2-14：服务未开启时弹窗内一键开启，不用退出搜索去设置。
                onEnableRemoteInput = { viewModel.enableRemoteInput() },
                // C11-P2-2：全局弹窗开着时扫码弹窗的返回处理让位给全局弹窗。
                globalModalOpen = globalModalOpen,
            )
        }

        // B36-P1-1：Toast 挂根 Box 末尾（与 B30-P2-1 的扫码弹窗、设置页
        // 同款），不参与根 Column 流式量测——之前是 Column 的流式子节点，
        // fillMaxSize 占满剩余高度，Toast 一出现 weight(1f) 的结果区即被
        // 量到 0 高、消失 2 秒（B30 的注释只记了扫码弹窗，Toast 漏了）。
        // B6-P2-11：Toast 底部居中。
        if (uiState.toast != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                OrionToast(
                    message = uiState.toast?.message ?: "",
                    modifier = Modifier.padding(bottom = 48.dp),
                )
            }
        }
    }
}

/** 搜索框方向键在 KeyDown 预览阶段是否应移焦点（可单测，第 73 轮）：
 * 取代旧的 KeyUp 快照法（shouldConsumeSearchLeftKeyUp/RightKeyUp）——
 * BasicTextField 在 KeyDown 就吃掉方向键做光标移动，等 KeyUp 再处理时
 * 焦点移动已无从发生（第 72 轮实测：搜索框里按 下/右 焦点出不去）。
 * 返回 true 时调用方用 FocusManager.moveFocus 移焦点并消费事件；
 * 返回 false 时放行，让输入框自己移光标。
 * - Left：光标在行首（start <= 0）才移焦点（左出到导航轨）；
 *   不在行首时只移光标。
 * - Right：光标在行尾（end >= textLength）才移焦点
 *   （右进“屏上键盘/手机扫码输入”按钮）；不在行尾时只移光标。
 * - Down/Up：永远移焦点——单行搜索框，无多行文本可移光标；
 *   键盘开着时 Down 进键盘，关着时 Down 进提示词/结果区。
 * 注意：光标位置取的是 KeyDown 预览阶段读到的值（移动前位置），
 * 与旧快照法等价，但判定与移动发生在同一次事件里，不再跨阶段。 */
internal fun shouldMoveSearchFocusOnKeyDown(
    key: Key,
    cursorStart: Int,
    cursorEnd: Int,
    textLength: Int,
): Boolean = when (key) {
    Key.DirectionLeft -> cursorStart <= 0
    Key.DirectionRight -> cursorEnd >= textLength
    Key.DirectionDown, Key.DirectionUp -> true
    else -> false
}

/**
 * NavHost 级弹窗关闭的 tick 是否应跑搜索页焦点恢复（可单测，C12-P1-5）：
 * 全局弹窗开着时不抢（关闭后 tick +1 重跑）；本页扫码弹窗开着时也不抢——
 * tick 恢复会把焦点抢出扫码弹窗，扫码弹窗关闭后由它自己的关闭 effect
 * 把焦点还给触发点。
 */
internal fun shouldRunSearchTickRestore(globalModalOpen: Boolean, remoteInputModalOpen: Boolean): Boolean =
    !globalModalOpen && !remoteInputModalOpen

/**
 * 屏上键盘的 BackHandler 是否启用（可单测，C12-P2-1）：键盘开着且全局弹窗
 * 未开着时才启用——后注册优先于 NavHost 的全局 handler，全局弹窗开着时
 * 返回键应先关全局弹窗，而不是先收底下的键盘。
 */
internal fun shouldEnableKeyboardBackHandler(showKeyboard: Boolean, globalModalOpen: Boolean): Boolean =
    showKeyboard && !globalModalOpen

/**
 * 扫码弹窗的 BackHandler 是否启用（可单测，C13-P1-2）：扫码弹窗开着且全局
 * 弹窗未开着时才启用——与键盘 handler 同款谓词结构；之前漏了全局弹窗门控，
 * 全局弹窗开着时返回键会先关底下的扫码弹窗、而不是先关视觉上在上的全局弹窗。
 */
internal fun shouldEnableRemoteInputBackHandler(remoteInputModalOpen: Boolean, globalModalOpen: Boolean): Boolean =
    remoteInputModalOpen && !globalModalOpen
