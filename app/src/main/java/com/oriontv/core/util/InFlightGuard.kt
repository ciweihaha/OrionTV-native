package com.oriontv.core.util

/**
 * 第 6 轮 P1-2：翻转类操作的防连按守卫（纯逻辑，可单测）。
 *
 * 背景：收藏 toggle 是“读-改-写翻转”，不是幂等操作——快速连按两次 =
 * 翻转两次 = 回到原态（删了又加回来），且无任何提示。仓库层的 writeMutex
 * 只串行化调用，每次 toggle 都重新读最新状态再翻转，拦不住连按。
 * 调用方在发起操作前 [tryAcquire]，操作结束（成功/失败/取消）后 [release]；
 * 拿不到锁的操作直接丢弃，不再发起第二次 toggle。
 * 键缺省为单键（详情页“收藏”只有一个入口）；收藏页页内删除按记录键
 * 区分（不同卡片的删除互不阻塞）。
 * 注意：守卫只防本进程内连按；跨端并发仍需服务端原子接口（数据层 D-P2-13）。
 */
class InFlightGuard {
    private val inFlight = mutableSetOf<String>()

    /** 拿不到锁返回 false（调用方直接丢弃本次操作）；拿到返回 true。 */
    fun tryAcquire(key: String = DEFAULT_KEY): Boolean = inFlight.add(key)

    /** 操作结束必须释放，否则该键永久被拦。 */
    fun release(key: String = DEFAULT_KEY) {
        inFlight.remove(key)
    }

    /** 该键的操作是否进行中（供按钮禁用态用）。 */
    fun isInFlight(key: String = DEFAULT_KEY): Boolean = key in inFlight

    companion object {
        const val DEFAULT_KEY = "default"
    }
}
