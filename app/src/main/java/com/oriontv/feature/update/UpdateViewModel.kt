package com.oriontv.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.BuildConfig
import com.oriontv.core.util.VersionUtils
import com.oriontv.data.api.AggApi
import com.oriontv.data.repo.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 更新 ViewModel（plan §5.1/§5.5/§6 M3）：
 * 冷启动 12 小时静默检查 + 手动检查 + 下载（上限 200MB、失败重试 3 次、
 * 退避 3s × 次数）+ SHA256 校验（拿不到/不一致都拒绝安装）+ 系统安装器安装
 * + 跳过版本；旧 APK 只保留最新 2 个。
 * 每一步失败都有明确提示（error/toast），不静默失败；安装进行中防连按。
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val aggApi: AggApi,
    private val settingsRepository: SettingsRepository,
    private val downloader: UpdateDownloader,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    // debug 构建的 VERSION_NAME 带 "-debug" 后缀：比对只用数字段，否则 debug 包恒判“有更新”。
    private val _uiState = MutableStateFlow(
        UpdateUiState(currentVersion = BuildConfig.VERSION_NAME.substringBefore("-")),
    )
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private var installInFlight = false

    /** 当前版本是否低于目标版本。 */
    fun needsUpdate(currentVersion: String, latestVersion: String): Boolean =
        VersionUtils.isOlderThan(currentVersion, latestVersion)

    /**
     * 检查更新。silent=true 时只在距上次检查 >= 12 小时才发请求
     *（plan §5.6：须等 lastCheckTime 读回后再算间隔——这里用 first() 等 DataStore
     * 发出已持久化的值再判断，不会用初始值 0 误判）。
     */
    fun checkForUpdate(silent: Boolean = false) {
        if (_uiState.value.isChecking) return
        viewModelScope.launch {
            _uiState.update { it.copy(isChecking = true, error = null, toast = null) }
            try {
                if (silent) {
                    val lastCheck = settingsRepository.currentUpdateLastCheckTime()
                    if (!UpdateService.shouldCheckNow(lastCheck)) {
                        _uiState.update { it.copy(isChecking = false) }
                        return@launch
                    }
                }
                val latest = aggApi.version()
                settingsRepository.setUpdateLastCheckTime(System.currentTimeMillis())
                val skipVersion = settingsRepository.currentUpdateSkipVersion()
                val current = _uiState.value.currentVersion
                when {
                    !needsUpdate(current, latest) -> {
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                isLatest = true,
                                updateAvailable = false,
                                showUpdateCard = false,
                                toast = if (silent) null else "已是最新版本 v$current",
                            )
                        }
                    }
                    UpdateService.isSkippedVersion(latest, skipVersion) -> {
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                updateAvailable = true,
                                showUpdateCard = false,
                                toast = if (silent) null else "已跳过 v$latest，可在设置里重新检查",
                            )
                        }
                    }
                    else -> {
                        val size = downloader.headApkSizeBytes(UpdateService.downloadUrl(latest))
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                apkSizeBytes = size,
                                updateAvailable = true,
                                // 静默检查发现新版也要弹卡（RN 教训：静默恒不弹等于自动更新无效）。
                                showUpdateCard = true,
                                isLatest = false,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        error = "检查更新失败：${e.message ?: "网络错误"}，请检查网络后重试",
                    )
                }
            }
        }
    }

    /** 开始下载 + SHA256 校验（进度进 state；失败留 error，不静默）。 */
    fun startDownload() {
        val latest = _uiState.value.latestVersion ?: return
        if (_uiState.value.downloading) return
        viewModelScope.launch {
            _uiState.update { it.copy(downloading = true, downloadProgress = 0, error = null) }
            try {
                val dir = File(context.filesDir, "update").apply { mkdirs() }
                downloader.cleanOldApkFiles(dir)
                val dest = File(dir, "OrionTV_v${System.currentTimeMillis()}.apk")
                downloader.downloadAndVerify(latest, dest) { written, total ->
                    val progress = if (total > 0) ((written * 100 / total).toInt()) else 0
                    _uiState.update { it.copy(downloadProgress = progress.coerceIn(0, 100)) }
                }
                _uiState.update {
                    it.copy(
                        downloading = false,
                        downloadProgress = 100,
                        downloadedFilePath = dest.absolutePath,
                        toast = "下载完成，校验通过，可以安装了",
                    )
                }
            } catch (e: UpdateDownloadException) {
                _uiState.update {
                    it.copy(downloading = false, error = e.message ?: "下载失败")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(downloading = false, error = "下载失败：${e.message ?: "网络错误"}")
                }
            }
        }
    }

    /** 调起系统安装器（防连按；调不起时报明确错误）。 */
    fun installUpdate() {
        if (installInFlight) return
        val path = _uiState.value.downloadedFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            _uiState.update { it.copy(error = "安装失败：安装包文件已不存在，请重新下载") }
            return
        }
        installInFlight = true
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(context.packageManager) == null) {
                _uiState.update { it.copy(error = "安装失败：找不到系统安装器") }
                return
            }
            context.startActivity(intent)
            _uiState.update { it.copy(showUpdateCard = false) }
        } catch (e: Exception) {
            _uiState.update { it.copy(error = "安装失败：${e.message ?: "无法调起安装器"}") }
        } finally {
            installInFlight = false
        }
    }

    /** 跳过此版：记下来，下次检查不再弹卡。 */
    fun skipVersion() {
        val latest = _uiState.value.latestVersion ?: return
        viewModelScope.launch {
            settingsRepository.setUpdateSkipVersion(latest)
            _uiState.update {
                it.copy(showUpdateCard = false, toast = "已跳过 v$latest")
            }
        }
    }

    fun dismissCard() {
        _uiState.update { it.copy(showUpdateCard = false) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class UpdateUiState(
    val currentVersion: String = "",
    val isChecking: Boolean = false,
    val isLatest: Boolean = false,
    val latestVersion: String? = null,
    val apkSizeBytes: Long? = null,
    val updateAvailable: Boolean = false,
    val showUpdateCard: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Int = 0,
    val downloadedFilePath: String? = null,
    val error: String? = null,
    /** 一次性提示（Toast，2 秒消失，UI 消费后清掉）。 */
    val toast: String? = null,
)
