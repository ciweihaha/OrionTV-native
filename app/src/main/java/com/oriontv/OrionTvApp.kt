package com.oriontv

import android.app.Application
import com.oriontv.core.util.AppLog
import com.oriontv.core.util.TlsTrust
import com.oriontv.data.local.RnMigrationEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp

/** OrionTV 原生版 Application，Hilt 入口。 */
@HiltAndroidApp
class OrionTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // v2.0.7：旧系统（Android 7.0）信任库缺 ISRG Root X1，Let's Encrypt
        // 的流地址握手失败（Trust anchor not found）——ExoPlayer 建出来
        // 之前先把内置根装为默认信任，否则点播/直播都播不了。
        TlsTrust.installExtraTrustRoots(this)
        // D-P1-6：RN 首启迁移（与 RN 同包名 com.oriontv 升级时，老用户数据在
        // AsyncStorage 里；只跑一次，失败下次启动重试；旧库只读不删）。
        // Application.onCreate 是触发点——不碰任何 UI 文件。
        runCatching {
            EntryPointAccessors.fromApplication(this, RnMigrationEntryPoint::class.java)
                .rnMigration().start()
        }.onFailure { e ->
            AppLog.w("OrionTvApp", "RN 首启迁移启动失败：${e.message}")
        }
    }
}
