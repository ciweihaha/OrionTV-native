package com.oriontv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.oriontv.core.focus.onKeyUp
import com.oriontv.navigation.OrionRoute
import com.oriontv.ui.theme.OrionColors
import com.oriontv.ui.theme.OrionDimens
import com.oriontv.ui.theme.OrionTextStyles

/**
 * 左侧导航轨（ui-design.md §1/§6）：
 * 96dp 宽常驻；5 项（首页/直播/搜索/收藏/设置），图标 + 8sp 小字竖排；
 * 焦点高亮当前项；右键进内容区，左键回导航轨（焦点移动由系统焦点链处理）；
 * 播放页全屏视频时不显示（OrionNavHost 控制）。
 */
data class NavRailItem(
    val label: String,
    val icon: ImageVector,
    val route: OrionRoute,
)

val NavRailItems = listOf(
    NavRailItem("首页", Icons.Filled.Home, OrionRoute.Home),
    NavRailItem("直播", Icons.Filled.LiveTv, OrionRoute.Live),
    NavRailItem("搜索", Icons.Filled.Search, OrionRoute.Search),
    NavRailItem("收藏", Icons.Filled.Favorite, OrionRoute.Favorites),
    NavRailItem("设置", Icons.Filled.Settings, OrionRoute.Settings),
)

@Composable
fun NavRail(
    currentRoute: OrionRoute?,
    onNavigate: (OrionRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(OrionDimens.NavRailWidth)
            .fillMaxHeight()
            .background(OrionColors.Background)
            .padding(vertical = OrionDimens.ContentPadding.calculateTopPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (item in NavRailItems) {
            NavRailEntry(
                item = item,
                selected = currentRoute == item.route,
                onClick = { onNavigate(item.route) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun NavRailEntry(
    item: NavRailItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(72.dp)
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
                androidx.compose.foundation.shape.RoundedCornerShape(OrionDimens.CardCornerRadius),
            )
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = when {
                    selected -> OrionColors.Brand
                    focused -> OrionColors.Text
                    else -> OrionColors.TextSecondary
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.label,
                style = OrionTextStyles.NavLabel,
                color = if (selected) OrionColors.Text else OrionTextStyles.NavLabel.color,
            )
        }
    }
}
