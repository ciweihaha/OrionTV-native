package com.oriontv.feature.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

// 第 7 轮 P2-5：已下载包路径的持久化——与设置类 DataStore 分开的独立偏好文件
//（只归更新功能所有，不碰数据层的 SettingsDataStore/SettingsRepository）。
// 进程重启后 UpdateViewModel 启动时恢复并校验（见 validateRestoredDownload），
// 文件仍存在且版本匹配才复用，不存在/不匹配则清理记录、删孤儿包。
private val Context.downloadedApkDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "downloaded_apk",
    // C4-P2-6 同款：偏好文件损坏时用空偏好重建，不抛 CorruptionException
    // 导致启动即崩溃循环。
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** 已下载包的持久化记录：磁盘路径 + 对应版本。 */
data class DownloadedApk(val path: String, val version: String)

/**
 * 已下载包记录的 DataStore 存取（第 7 轮 P2-5）。
 * 只做存取，校验（文件仍存在、文件名与版本匹配）由调用方
 * [validateRestoredDownload] 做，便于单测。
 */
class DownloadedApkStore(private val context: Context) {
    private object Keys {
        val PATH = stringPreferencesKey("downloaded_apk_path")
        val VERSION = stringPreferencesKey("downloaded_apk_version")
    }

    /** 读回记录；路径或版本缺失时视为无记录。 */
    suspend fun load(): DownloadedApk? {
        val prefs = context.downloadedApkDataStore.data.first()
        val path = prefs[Keys.PATH].orEmpty()
        val version = prefs[Keys.VERSION].orEmpty()
        return if (path.isBlank() || version.isBlank()) null else DownloadedApk(path, version)
    }

    /** 下载完成时记下路径 + 版本。 */
    suspend fun save(path: String, version: String) {
        context.downloadedApkDataStore.edit {
            it[Keys.PATH] = path
            it[Keys.VERSION] = version
        }
    }

    /** 记录失效/被清理时清掉（不删文件，文件由调用方按校验结果处理）。 */
    suspend fun clear() {
        context.downloadedApkDataStore.edit {
            it.remove(Keys.PATH)
            it.remove(Keys.VERSION)
        }
    }
}

/**
 * 第 7 轮 P2-5：恢复已下载包记录的校验（纯函数，可单测）——
 * 路径与版本非空、文件仍存在、文件名就是该版本的固定名
 * （OrionTV_v<版本>.apk，与 startDownload 的 dest 命名一致）。
 * 任一不满足都视为无效：调用方清记录；文件存在但对不上号时再删文件，
 * 不留孤儿包（见 isOrphanDownloadFile）。
 */
internal fun validateRestoredDownload(path: String?, version: String?): Boolean {
    if (path.isNullOrBlank() || version.isNullOrBlank()) return false
    val file = java.io.File(path)
    return file.exists() && file.name == "OrionTV_v$version.apk"
}

/**
 * 第 7 轮 P2-5：恢复出的记录是否对应磁盘上的孤儿包——文件存在但校验
 * 不通过（版本对不上文件名等），调用方删掉它再清记录。
 * 文件根本不在时没有孤儿可删，返回 false。
 */
internal fun isOrphanDownloadFile(path: String?, version: String?): Boolean {
    if (path.isNullOrBlank()) return false
    val file = java.io.File(path)
    return file.exists() && !validateRestoredDownload(path, version)
}
