package com.oriontv.core.storage

/**
 * 本地存储键名，照抄 plan §5.4（AsyncStorage 键名，一字不差，迁移也要用这套）。
 */
object StorageKeys {
    const val SETTINGS = "mytv_settings"
    const val PLAYER_SETTINGS = "mytv_player_settings"
    const val FAVORITES = "mytv_favorites"
    const val PLAY_RECORDS = "mytv_play_records"
    const val SEARCH_HISTORY = "mytv_search_history"

    /** 系统安全存储（EncryptedSharedPreferences），登录凭据 JSON。 */
    const val LOGIN_CREDENTIALS = "mytv_login_credentials"

    /** 仅登录标记位，"1" 或 ""，不是 token；真 cookie 在 OkHttp CookieJar 中。 */
    const val AUTH_COOKIES = "authCookies"

    const val M3U_CACHE_PREFIX = "mytv_m3u_cache_"
    const val EPG_CACHE_PREFIX = "mytv_epg_cache_"
    const val LIVE_LAST_CHANNEL = "mytv_live_last_channel"
    const val LIVE_FAV_CHANNELS = "mytv_live_fav_channels"

    const val UPDATE_LAST_CHECK_TIME = "update_last_check_time"
    const val UPDATE_SKIP_VERSION = "update_skip_version"

    const val PAIR_CODE = "orion_pair_code"

    fun m3uCacheKey(m3uUrl: String): String = M3U_CACHE_PREFIX + m3uUrl

    fun epgCacheKey(epgUrl: String): String = EPG_CACHE_PREFIX + epgUrl
}
