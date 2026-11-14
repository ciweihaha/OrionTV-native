package com.oriontv.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.oriontv.ui.theme.OrionTextStyles

/** 设置页占位 Screen。M3 补：纵向单列滚动 5 个 section + 手工焦点机 + 底部右对齐“保存设置”按钮。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "设置页（骨架占位，M3 实现）", style = OrionTextStyles.Title)
    }
}
