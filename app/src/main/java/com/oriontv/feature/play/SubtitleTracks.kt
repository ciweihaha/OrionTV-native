package com.oriontv.feature.play

/**
 * 字幕轨道选择（第 70 轮综合改进①：内嵌字幕）。
 *
 * 背景：点播/直播流里常带内嵌字幕轨（中英双语居多），之前播放器从不选字幕轨、
 * 控制条也没有字幕入口，外语片只能干看画面。Media3 的 DefaultTrackSelector
 * 默认不替用户选字幕轨（语言偏好只影响音频轨），须显式下发 override。
 *
 * 分层：本文件只放纯逻辑（可单测，不碰 ExoPlayer）；真正读写
 * `player.currentTracks / trackSelectionParameters` 的薄桥在 PlayerManager
 * （`availableSubtitleTracks()` / `setSubtitleTrack()`），桥里包 try/catch，
 * 旧轨失效/播放器异常时不崩溃。PlayViewModel 在 onTracksChanged 里调刷新，
 * 控制条的字幕按钮只做“循环切换”，不另开抽屉。
 */
data class SubtitleTrackInfo(
    /** 展示用名（流内 label，缺失时由语言派生，见 [subtitleDisplayName]）。 */
    val label: String,
    /** 语言码（如 zh / chi / en / eng，可能带地区后缀 zh-CN）。 */
    val language: String?,
    /** Media3 TrackGroup 在 currentTracks.groups 里的下标（override 用）。 */
    val groupIndex: Int,
    /** 该组内的轨道下标（override 用）。 */
    val trackIndex: Int,
)

/** 语言码归一化：小写、去地区后缀（zh-CN / zh_CN → zh）。 */
internal fun normalizeSubtitleLanguage(language: String?): String =
    language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_').orEmpty()

/** 该轨道是不是中文（语言码 zh/chi/zho，或 label 里写明中文/Chinese）。 */
internal fun isChineseSubtitleTrack(track: SubtitleTrackInfo): Boolean {
    val lang = normalizeSubtitleLanguage(track.language)
    if (lang == "zh" || lang == "chi" || lang == "zho") return true
    val label = track.label.lowercase()
    return label.contains("中文") || label.contains("chinese") || label.contains("简体") || label.contains("繁体")
}

/** 该轨道是不是英文（语言码 en/eng，或 label 里写明英文/English）。 */
internal fun isEnglishSubtitleTrack(track: SubtitleTrackInfo): Boolean {
    val lang = normalizeSubtitleLanguage(track.language)
    if (lang == "en" || lang == "eng") return true
    val label = track.label.lowercase()
    return label.contains("英文") || label.contains("english")
}

/**
 * 轨道展示名（可单测）：流内 label 优先（“中文”“English”“字幕 1”之类直接能看）；
 * label 缺失时按语言码派生（中文/English/语言码本身）；两者都没有才叫“字幕”。
 */
fun subtitleDisplayName(track: SubtitleTrackInfo): String {
    val label = track.label.trim()
    if (label.isNotEmpty()) return label
    return when (normalizeSubtitleLanguage(track.language)) {
        "zh", "chi", "zho" -> "中文"
        "en", "eng" -> "English"
        "" -> "字幕"
        else -> track.language?.trim().orEmpty()
    }
}

/**
 * 自动首选字幕轨（可单测）：中文 > 英文 > 其他（同分取第一条）。
 * 只有“用户没手动选过、且是新片/原选中轨消失”时 PlayViewModel 才调它自动选；
 * 手动选过的不覆盖。
 */
fun choosePreferredSubtitleTrack(tracks: List<SubtitleTrackInfo>): Int? {
    if (tracks.isEmpty()) return null
    var bestIndex: Int? = null
    var bestScore = Int.MAX_VALUE
    for ((index, track) in tracks.withIndex()) {
        val score = when {
            isChineseSubtitleTrack(track) -> 0
            isEnglishSubtitleTrack(track) -> 1
            else -> 2
        }
        if (score < bestScore) {
            bestScore = score
            bestIndex = index
        }
    }
    return bestIndex
}

/**
 * 循环切换（可单测）：关 → 第 1 条 → 第 2 条 → … → 关。
 * 当前选中已不在列表里（换片后旧下标失效）时从第 1 条开始，不直接跳到关。
 */
fun nextSubtitleSelection(
    tracks: List<SubtitleTrackInfo>,
    currentSelectedIndex: Int?,
): Int? {
    if (tracks.isEmpty()) return null
    if (currentSelectedIndex == null) return 0
    if (currentSelectedIndex < 0 || currentSelectedIndex >= tracks.size) return 0
    val next = currentSelectedIndex + 1
    return if (next < tracks.size) next else null
}

/**
 * 控制条字幕按钮文案（可单测）：无轨 → “字幕：无字幕”；有关闭态 → “字幕：关”；
 * 选中某轨 → “字幕：中文” / “字幕：English” / “字幕：<展示名>”。
 */
fun subtitleButtonLabel(tracks: List<SubtitleTrackInfo>, selectedIndex: Int?): String {
    if (tracks.isEmpty()) return "字幕：无字幕"
    val selected = selectedIndex?.let { tracks.getOrNull(it) } ?: return "字幕：关"
    return "字幕：${subtitleDisplayName(selected)}"
}

/** 字幕刷新决策（可单测）：新列表下的选中下标 + 是否需要向播放器下发。 */
data class SubtitleRefreshDecision(
    val selectedIndex: Int?,
    val trackToApply: SubtitleTrackInfo?,
    val shouldApply: Boolean,
)

/**
 * onTracksChanged 后的选中决策（可单测，PlayViewModel.refreshSubtitleTracks 用）。
 *
 * - 旧选中轨还在新列表里（按 label + 语言认）→ 原位保留，不重新自动选；
 * - 用户没手动选过、且旧列表为空（新片首播）或旧选中轨消失 → 自动首选中文轨；
 * - 用户手动选过且旧选中轨消失 → 关字幕（不替用户再猜）；
 * - 无轨 → 关。
 * shouldApply 只在“要下发的轨 ≠ 上次已下发的轨”时为真，同一轨不重复下发 override。
 */
fun resolveSubtitleRefresh(
    oldTracks: List<SubtitleTrackInfo>,
    oldSelectedIndex: Int?,
    newTracks: List<SubtitleTrackInfo>,
    manualChoice: Boolean,
    lastApplied: SubtitleTrackInfo?,
): SubtitleRefreshDecision {
    if (newTracks.isEmpty()) {
        return SubtitleRefreshDecision(null, null, lastApplied != null)
    }
    val oldSelectedTrack = oldSelectedIndex?.let { oldTracks.getOrNull(it) }
    val remappedIndex = oldSelectedTrack?.let { selected ->
        newTracks.indexOfFirst { it.label == selected.label && it.language == selected.language }
            .takeIf { it >= 0 }
    }
    val targetIndex = when {
        !manualChoice && (oldTracks.isEmpty() || remappedIndex == null) ->
            choosePreferredSubtitleTrack(newTracks)
        else -> remappedIndex
    }
    val trackToApply = targetIndex?.let { newTracks[it] }
    return SubtitleRefreshDecision(targetIndex, trackToApply, trackToApply != lastApplied)
}
