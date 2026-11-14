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
import com.oriontv.ui.components.ToastEvent
import com.oriontv.ui.components.nextToastEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

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

    /** 进行中的 APK 下载任务（B5-P2-6：取消入口用）。 */
    private var downloadJob: Job? = null

    /** 当前版本是否低于目标版本。 */
    fun needsUpdate(currentVersion: String, latestVersion: String): Boolean =
        VersionUtils.isOlderThan(currentVersion, stripVersionPrefix(latestVersion))

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
                val latest = stripVersionPrefix(aggApi.version())
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
                                // B-P1-4：旧版下好的包已失效（当前已是最新），不清会残留“立即安装”。
                                downloadedFilePath = null,
                                toast = if (silent) null else nextToastEvent("已是最新版本 v$current"),
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
                                // B-P1-4：同上，跳过的新版不是已下载的旧包。
                                downloadedFilePath = null,
                                toast = if (silent) null else nextToastEvent("已跳过 v$latest，可在设置里重新检查"),
                            )
                        }
                    }
                    else -> {
                        val size = downloader.headApkSizeBytes(UpdateService.downloadUrl(latest))
                        // B4-P2-11：HEAD 体积超 200MB 直接报错、不弹下载卡（UpdateCard
                        // 的 apkTooLarge 禁用按钮是第二层；这里是第一层拦截）。
                        if (size != null && size > UpdateDownloader.MAX_APK_BYTES) {
                            _uiState.update {
                                it.copy(
                                    isChecking = false,
                                    latestVersion = latest,
                                    apkSizeBytes = size,
                                    updateAvailable = false,
                                    error = "安装包过大（${downloader.formatSize(size)}），超过 200MB 上限",
                                )
                            }
                            return@launch
                        }
                        // P2-12：新版卡片弹出时复位安装守卫（上一次安装流程已结束）。
                        installInFlight = false
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                apkSizeBytes = size,
                                updateAvailable = true,
                                // 静默检查发现新版也要弹卡（RN 教训：静默恒不弹等于自动更新无效）。
                                showUpdateCard = true,
                                isLatest = false,
                                // B-P1-4：新版≠已下载的旧包，清掉旧路径，防卡片“立即安装”装旧包。
                                downloadedFilePath = null,
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // B5-P1-1：AggApi.version() 的 60 秒总超时是 TimeoutCancellationException
                //（CancellationException 的子类），按普通失败落错误态、清 isChecking；
                // 只有真取消（JobCancellationException）才重抛。
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        error = "检查更新超时，请检查网络后重试",
                    )
                }
            } catch (e: CancellationException) {
                // D-P2-2：取消原样抛出，不转成 error 状态（ViewModel 清掉时本应直接取消）。
                throw e
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
        // B5-P2-6：下载任务留 Job + 取消入口；取消后 downloading 清掉，
        // 否则 startDownload 开头的 downloading 守卫会一直拦、再也下不了。
        downloadJob = viewModelScope.launch {
            _uiState.update { it.copy(downloading = true, downloadProgress = 0, error = null) }
            try {
                val dir = File(context.filesDir, "update").apply { mkdirs() }
                // D-P1-5：目标文件名按版本固定（不用时间戳），中断后再次下载能命中
                // 同一残文件、从断点续下；D-P2-18：删掉这里重复的清旧包，
                // downloadAndVerify 内部已在校验通过后清理。
                val dest = File(dir, "OrionTV_v$latest.apk")
                downloader.downloadAndVerify(latest, dest) { written, total ->
                    val progress = if (total > 0) ((written * 100 / total).toInt()) else 0
                    _uiState.update { it.copy(downloadProgress = progress.coerceIn(0, 100)) }
                }
                _uiState.update {
                    it.copy(
                        downloading = false,
                        downloadProgress = 100,
                        downloadedFilePath = dest.absolutePath,
                        toast = nextToastEvent("下载完成，校验通过，可以安装了"),
                    )
                }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(downloading = false) }
                throw e
            } catch (e: UpdateDownloadException) {
                _uiState.update {
                    // B-P1-4：下载失败的半截包不可留“已下载”态，下次重下。
                    it.copy(downloading = false, downloadedFilePath = null, error = e.message ?: "下载失败")
                }
            } catch (e: CancellationException) {
                // D-P2-2：取消原样抛出，不转成 error 状态。
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        downloading = false,
                        downloadedFilePath = null,
                        error = "下载失败：${e.message ?: "网络错误"}",
                    )
                }
            } finally {
                downloadJob = null
            }
        }
    }

    /** 取消进行中的 APK 下载（更新卡片的取消入口用；未在下载时是空操作）。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
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
                installInFlight = false
                return
            }
            context.startActivity(intent)
            _uiState.update { it.copy(showUpdateCard = false) }
            // P2-12（第 5 轮）：成功调起后守卫保持 armed，不在 finally 里复位——
            // 第二次点按是另一个独立事件，发生在 finally 之后，原来的同步
            // 置 true 又置 false 恒放行。卡片已关闭，复位点在 dismissCard() /
            // skipVersion() / 下次检查出新版时；调起失败时下面 catch 里复位，
            // 允许用户重试。
            // 注意（rebase 合并取舍，见报告）：B4-P2-12 曾让卡片调起后保持打开、
            // 以便取消安装后直接重试，但与 P2-12 的跨事件守卫不兼容——卡片开着
            // 时守卫 armed，再点“立即安装”会被静默拦掉，重试点成死按钮。
            // 两害相权取其轻：保 P2-12（防重复调起安装器是正确性问题），
            // 取消安装后重试走“重新检查更新”（新版卡片弹出时复位守卫）。
            // B4-P2-12 的另一半仍保留：不立即清 downloadedFilePath，取消安装
            // 后重查仍显示“立即安装”，不用重新下载。
        } catch (e: Exception) {
            installInFlight = false
            _uiState.update { it.copy(error = "安装失败：${e.message ?: "无法调起安装器"}") }
        }
    }

    /** 跳过此版：记下来，下次检查不再弹卡。 */
    fun skipVersion() {
        val latest = _uiState.value.latestVersion ?: return
        installInFlight = false
        viewModelScope.launch {
            settingsRepository.setUpdateSkipVersion(latest)
            _uiState.update {
                it.copy(showUpdateCard = false, toast = nextToastEvent("已跳过 v$latest"))
            }
        }
    }

    fun dismissCard() {
        installInFlight = false
        _uiState.update { it.copy(showUpdateCard = false) }
    }

    fun consumeToast() {
        _uiState.update { it.copy(toast = null) }
    }
}

/**
 * P2-22：版本号先去 "v"/"V" 前缀再比较/拼下载地址。
 * B4-P2-13：AggApi.version() 内部已 strip v 前缀（根治），这里的 strip
 * 是死防御，留着无害；第 5 轮起根治点在 AggApi（先 strip 再校验），这里只留兼容委托。
 * 纯逻辑，可单测。
 */
fun stripVersionPrefix(version: String): String = VersionUtils.stripVersionPrefix(version)

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
    val toast: ToastEvent? = null,
)
