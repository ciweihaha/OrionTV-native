package com.oriontv.feature.home

/**
 * 首页分类行分页状态机（分类浏览分页）。
 *
 * 首页各行首屏只取第 1 页（CMS 20 条 / 豆瓣 20 条），焦点移到行尾附近时
 * [HomeViewModel.loadMore] 接着取下一页。改编自安卓版第 9 轮的 PagingState
 *（ui/vod/PagingState.kt）：同样的“空页不立即判死、连续两页空才停、失败
 * 可重试、加载中不重入”语义，区别是游标分两种——CMS 按页号（pg），豆瓣
 * 按偏移（pageStart）。
 *
 * 纯逻辑类，不依赖 Android，可单测。ViewModel 每行持有一个实例（行标题
 * 为键）；refresh() 重建行时状态机随之重建，分页从第 1 页重新开始。
 * 最近播放行是本地播放记录，不分页，不建状态机。
 */
class RowPagingState(
    /** 该行第 1 页实际走的源（CMS 首屏失败回落豆瓣时记 DOUBAN，不混源翻页）。 */
    val kind: RowSource,
    /** CMS 分类的 t（kind=CMS 时用；null 表示不是 CMS 行）。 */
    val cmsType: Int? = null,
    /** 豆瓣的 type（kind=DOUBAN 时用，第 1 页解析出的实际值）。 */
    val doubanType: String? = null,
    /** 豆瓣的 tag（kind=DOUBAN 时用，第 1 页解析出的实际值）。 */
    val doubanTag: String? = null,
) {
    /** 下一次要请求的游标：CMS 是页号（第 1 页已取完，从 2 开始），豆瓣是偏移（第 1 页 20 条，从 20 开始）。 */
    var nextCursor: Int = if (kind == RowSource.CMS) CMS_FIRST_NEXT_PAGE else DOUBAN_PAGE_SIZE
        private set

    /** 是否还有下一页可取（连续两页空、或已明确到底时为 false）。 */
    var hasMore: Boolean = true
        private set

    /** 是否正在取下一页（取数中不重入）。 */
    var isLoading: Boolean = false
        private set

    /** 连续返回空页的次数（M10 同款：单次空页不判死）。 */
    var consecutiveEmptyPages: Int = 0
        private set

    fun canLoadMore(): Boolean = !isLoading && hasMore

    fun onLoadStarted() {
        isLoading = true
    }

    /** 一页取回非空：游标推进到下一次，空页计数清零。 */
    fun onPageLoaded(advancedCursor: Int) {
        isLoading = false
        nextCursor = advancedCursor
        consecutiveEmptyPages = 0
    }

    /**
     * 一页取回空：游标照样推进（跳过这页，下次取再下一页），空页计数 +1；
     * 连续两页空才判到底（M10）——单次空页可能是服务端分页抖动，
     * 直接判死会让后面明明有的页永远取不到；无限重试空页同样不行。
     */
    fun onEmptyPage(advancedCursor: Int) {
        isLoading = false
        nextCursor = advancedCursor
        consecutiveEmptyPages++
        if (consecutiveEmptyPages >= MAX_CONSECUTIVE_EMPTY_PAGES) {
            hasMore = false
        }
    }

    /** 取页失败：游标与 hasMore 不动（失败可重试，焦点移开再回来即重试），只清加载中。 */
    fun onLoadFailed() {
        isLoading = false
    }

    /**
     * 焦点是否已到行尾附近、可以触发下一页。电视遥控按方向键逐张移动，
     * 焦点位置即滚动位置；[focusedIndex] 是当前聚焦卡片的下标，
     * [totalCount] 是行内现有卡片数。空行不触发。
     */
    fun shouldLoadMore(focusedIndex: Int, totalCount: Int, threshold: Int = FOCUS_THRESHOLD): Boolean =
        canLoadMore() &&
            totalCount > 0 &&
            focusedIndex >= totalCount - threshold

    /** 给定当前游标，算下一游标：CMS 页号 +1，豆瓣偏移 +20。 */
    fun advanceCursor(cursor: Int): Int =
        if (kind == RowSource.CMS) cursor + 1 else cursor + DOUBAN_PAGE_SIZE

    companion object {
        /** 豆瓣每页条数（首屏与翻页一致）。 */
        const val DOUBAN_PAGE_SIZE = 20

        /** CMS 第 1 页取完后，下一页的页号。 */
        const val CMS_FIRST_NEXT_PAGE = 2

        /** 焦点离行尾还剩几张卡时触发加载下一页。 */
        const val FOCUS_THRESHOLD = 3

        /** 连续空页超过这个数就判到底，不再取。 */
        const val MAX_CONSECUTIVE_EMPTY_PAGES = 2
    }
}

/** 首页行第 1 页实际走的数据源（分页续取同一源，不混源）。 */
enum class RowSource {
    CMS,
    DOUBAN,
}
