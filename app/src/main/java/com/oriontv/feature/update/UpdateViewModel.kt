package com.oriontv.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oriontv.BuildConfig
import com.oriontv.core.util.AppLog
import com.oriontv.core.util.VersionUtils
import com.oriontv.data.api.AggApi
import com.oriontv.data.api.AggInvalidResponseException
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

    /** B7-P2-1：下载代次——取消后立即重下时，旧任务的 CE catch/finally 凭代次
     * 复核，过期任务不再盖掉新下载的状态、也不再把新任务的 Job 置 null。 */
    // B9-P2-4：进度回调在 IO 线程读它，plain var 无可见性保证，加 @Volatile
    //（与 Detail/SearchViewModel 的同款字段一致，这里漏了）。
    @Volatile
    private var downloadGeneration = 0

    /** 第 7 轮 P2-5：已下载包路径 + 版本的持久化（独立 DataStore，不碰设置层）。 */
    private val downloadedApkStore = DownloadedApkStore(context)

    /** 第 7 轮 P2-5：启动恢复是否已跑过（init 与 checkForUpdate 各调一次，只跑一次）。 */
    private var downloadRestoreDone = false

    init {
        viewModelScope.launch { restoreDownloadedApkOnce() }
    }

    /**
     * 第 7 轮 P2-5：启动时恢复已下载包——记录存在、文件仍在、文件名与版本
     * 匹配时复用（重查/卡片直接显示“立即安装”，不用重新下载）；否则清记录，
     * 文件存在但对不上号时删掉，不留孤儿包。
     */
    private suspend fun restoreDownloadedApkOnce() {
        if (downloadRestoreDone) return
        downloadRestoreDone = true
        val record = try {
            downloadedApkStore.load()
        } catch (_: Exception) {
            null
        } ?: return
        if (validateRestoredDownload(record.path, record.version)) {
            _uiState.update {
                it.copy(downloadedFilePath = record.path, downloadedVersion = record.version)
            }
        } else {
            if (isOrphanDownloadFile(record.path, record.version)) {
                runCatching { File(record.path).delete() }
            }
            runCatching { downloadedApkStore.clear() }
        }
    }

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
        // B7-P2-2：isChecking 守卫必须在 launch 前同步置位——旧实现在 launch
        // 体内才置位，两次快速调用都通过守卫、并发跑两路检查。
        _uiState.update { it.copy(isChecking = true, error = null, toast = null) }
        viewModelScope.launch {
            try {
                if (silent) {
                    val lastCheck = settingsRepository.currentUpdateLastCheckTime()
                    if (!UpdateService.shouldCheckNow(lastCheck)) {
                        _uiState.update { it.copy(isChecking = false) }
                        return@launch
                    }
                }
                val latest = stripVersionPrefix(aggApi.version())
                // B9-P2-2：成功后的附属读写失败不进主流程失败分支——版本已拿到，
                // 检查时间写失败只记日志；跳过版本读失败按“未跳过”走。
                runCatching { settingsRepository.setUpdateLastCheckTime(System.currentTimeMillis()) }
                    .onFailure { AppLog.w("UpdateViewModel", "检查时间保存失败：${it.message}") }
                val skipVersion = runCatching { settingsRepository.currentUpdateSkipVersion() }
                    .getOrDefault("")
                val current = _uiState.value.currentVersion
                // 第 7 轮 P2-5：先跑启动恢复（init 里那路可能还没跑完），
                // 再按恢复出的 downloadedVersion 判同版保留——否则重查
                // 会因恢复未完成而误判“从未下载过”，白白重新下载。
                restoreDownloadedApkOnce()
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
                                // 第 6 轮 P2-2：路径清掉时版本标记同步清掉，不留残值。
                                downloadedVersion = null,
                                toast = if (silent) null else nextToastEvent("已是最新版本 v$current"),
                            )
                        }
                        // 第 7 轮 P2-5：内存路径清掉时持久化记录同步清掉，
                        // 否则重启后恢复又把失效包复活。
                        // B11-P2-1：附属持久化失败不推翻主流程——版本已确认是
                        // 最新，clear() 抛错只记日志，不进外层 catch 的“检查失败”。
                        runCatching { downloadedApkStore.clear() }
                            .onFailure { AppLog.w("UpdateViewModel", "已下载记录清理失败：${it.message}") }
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
                                // 第 6 轮 P2-2：同上，版本标记同步清掉。
                                downloadedVersion = null,
                                toast = if (silent) null else nextToastEvent("已跳过 v$latest，可在设置里重新检查"),
                            )
                        }
                        // 第 7 轮 P2-5：同上，跳过版本的持久化记录同步清掉。
                        // B11-P2-1：同上，附属写失败只记日志、不报“检查失败”。
                        runCatching { downloadedApkStore.clear() }
                            .onFailure { AppLog.w("UpdateViewModel", "已下载记录清理失败：${it.message}") }
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
                        // 第 6 轮 P2-2：重查不再无条件清 downloadedFilePath——
                        // 文件名按版本固定（OrionTV_v$latest.apk），同版本时
                        // “新版≠已下载的旧包”的假设不成立；同版且文件还在就保留，
                        // 卡片直接显示“立即安装”，不用重新下载。版本不同或文件
                        // 已不在时才清掉（旧包失效）。
                        val keepDownload = shouldKeepDownloadedFile(
                            _uiState.value.downloadedVersion,
                            latest,
                        ) && _uiState.value.downloadedFilePath?.let { File(it).exists() } == true
                        _uiState.update {
                            it.copy(
                                isChecking = false,
                                latestVersion = latest,
                                apkSizeBytes = size,
                                updateAvailable = true,
                                // 静默检查发现新版也要弹卡（RN 教训：静默恒不弹等于自动更新无效）。
                                showUpdateCard = true,
                                isLatest = false,
                                downloadedFilePath = if (keepDownload) it.downloadedFilePath else null,
                                downloadedVersion = if (keepDownload) it.downloadedVersion else null,
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // B5-P1-1：AggApi.version() 的 60 秒总超时是 TimeoutCancellationException
                //（CancellationException 的子类），按普通失败落错误态、清 isChecking；
                // 只有真取消（JobCancellationException）才重抛。
                // B12-P2-7：静默检查失败不写 error——之前静默失败也走同一 catch
                // 置 error，设置页底部红字长期挂着、无任何路径清掉；静默失败只记日志。
                if (silent) AppLog.w("UpdateViewModel", "静默检查更新超时：${e.message}")
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        error = updateCheckErrorOrNull(silent, "检查更新超时，请检查网络后重试"),
                    )
                }
            } catch (e: CancellationException) {
                // D-P2-2：取消原样抛出，不转成 error 状态（ViewModel 清掉时本应直接取消）。
                throw e
            } catch (e: Exception) {
                // B12-P2-7：同上，静默检查失败不写 error，只记日志。
                if (silent) AppLog.w("UpdateViewModel", "静默检查更新失败：${e.message}")
                _uiState.update {
                    it.copy(
                        isChecking = false,
                        error = updateCheckErrorOrNull(silent, versionCheckError(e)),
                    )
                }
            }
        }
    }

    /** 开始下载 + SHA256 校验（进度进 state；失败留 error，不静默）。 */
    fun startDownload() {
        val latest = _uiState.value.latestVersion ?: return
        if (_uiState.value.downloading) return
        // 第 6 轮 P2-2（Jewel）：同版包已下好且文件还在，不重复下载（重查保留路径后
        // 卡片直接显示“立即安装”，正常走不到这里；多一层兜底）。
        val existingPath = _uiState.value.downloadedFilePath
        if (_uiState.value.downloadedVersion == latest &&
            existingPath != null && File(existingPath).exists()
        ) return
        // B6-P2-3：双发守卫必须在协程体外就生效——旧实现只在 launch 体内
        // 置 downloading=true，两次快速连点都通过守卫、起两个下载任务，
        // 后一个覆盖 downloadJob，前一个泄漏且 cancelDownload 停不掉。
        // 这里同步置位，第二次调用直接 return。
        _uiState.update { it.copy(downloading = true, downloadProgress = 0, error = null) }
        // B5-P2-6：下载任务留 Job + 取消入口；取消后 downloading 清掉，
        // 否则 startDownload 开头的 downloading 守卫会一直拦、再也下不了。
        // B7-P2-1：代次快照——CE catch/finally/失败分支只在代次仍是当前时
        // 写状态；取消后立即重下时，旧任务的收尾不再盖掉新下载。
        val downloadGen = ++downloadGeneration
        downloadJob = viewModelScope.launch {
            try {
                val dir = File(context.filesDir, "update").apply { mkdirs() }
                // D-P1-5：目标文件名按版本固定（不用时间戳），中断后再次下载能命中
                // 同一残文件、从断点续下；D-P2-18：删掉这里重复的清旧包，
                // downloadAndVerify 内部已在校验通过后清理。
                val dest = File(dir, "OrionTV_v$latest.apk")
                downloader.downloadAndVerify(latest, dest) { written, total ->
                    // B8-P2-4：进度回调也要复核代次——旧任务被新下载取代后，
                    // 它的进度回调仍会盖掉新任务的进度条（B7-P2-1 只修了
                    // CE/finally/失败分支，进度回调漏了）。
                    if (downloadGen != downloadGeneration) return@downloadAndVerify
                    val progress = if (total > 0) ((written * 100 / total).toInt()) else 0
                    _uiState.update { it.copy(downloadProgress = progress.coerceIn(0, 100)) }
                }
                if (downloadGen == downloadGeneration) {
                    // 第 7 轮 P2-4：完成时复核是否已被跳过——跳过此版后下载
                    // 才跑完（或跳过与完成竞态）时，不再弹“下载完成，可以安装了”
                    // Toast、不置已下载路径、不重弹卡片；“稍后”关掉卡片的情形
                    // 不受影响（见下面的重弹分支，同样以未跳过为前提）。
                    // B9-P2-2：同上——下载已完成、校验已通过，跳过版本读失败
                    // 只按“未跳过”走，不把已下好的包报成“下载失败”。
                    val skipVersion = runCatching { settingsRepository.currentUpdateSkipVersion() }
                        .getOrDefault("")
                    if (shouldNotifyDownloadComplete(latest, skipVersion)) {
                        _uiState.update {
                            it.copy(
                                downloading = false,
                                downloadProgress = 100,
                                downloadedFilePath = dest.absolutePath,
                                // 第 6 轮 P2-2：版本标记与路径同步置位（重查按版本比对保留同版包）。
                                downloadedVersion = latest,
                                toast = nextToastEvent("下载完成，校验通过，可以安装了"),
                            )
                        }
                        // 第 7 轮 P2-5：下载完成即持久化路径 + 版本，进程重启
                        // 后恢复复用，不用重新下载。
                        // B11-P2-1：附属持久化失败不推翻主流程——下载已完成、
                        // 校验已通过，save() 抛错只记日志，不把已下好的包报成
                        // “下载失败”（B9-P2-2 同款，不同调用点）。
                        runCatching { downloadedApkStore.save(dest.absolutePath, latest) }
                            .onFailure { AppLog.w("UpdateViewModel", "已下载记录保存失败：${it.message}") }
                        // 第 6 轮 P2-2：下载完成时卡片若已被“稍后”/返回键关掉，要重新
                        // 弹出给安装入口，否则下好的包没有任何入口能再打开“立即安装”
                        //（之前只发一条 Toast，卡片已关）；用户已“跳过此版”的不弹。
                        // 代次过期（已被更新的下载取代）时不弹旧任务的卡。
                        if (!_uiState.value.showUpdateCard) {
                            _uiState.update { it.copy(showUpdateCard = true) }
                        }
                    } else {
                        _uiState.update {
                            it.copy(downloading = false, downloadProgress = 100)
                        }
                        // 已跳过版本的包不要留在磁盘上成孤儿（下次 cleanOldApkFiles
                        // 才清要等下一次下载成功）；刚下好的就是这个文件，直接删。
                        runCatching { dest.delete() }
                    }
                }
            } catch (e: CancellationException) {
                if (downloadGen == downloadGeneration) {
                    _uiState.update { it.copy(downloading = false) }
                }
                throw e
            } catch (e: UpdateDownloadException) {
                if (downloadGen == downloadGeneration) {
                    _uiState.update {
                        // B-P1-4：下载失败的半截包不可留“已下载”态，下次重下。
                        // 第 6 轮 P2-2：版本标记与路径同步清掉。
                        it.copy(
                            downloading = false,
                            downloadedFilePath = null,
                            downloadedVersion = null,
                            error = e.message ?: "下载失败",
                        )
                    }
                    // 第 7 轮 P2-5：内存路径清掉时持久化记录同步清掉。
                    // B11-P2-1：同款——这里已在 catch 分支里，clear() 再抛错
                    // 会直接逃出 launch、变成 viewModelScope 未捕获异常崩应用；
                    // 附属写失败只记日志。
                    runCatching { downloadedApkStore.clear() }
                        .onFailure { AppLog.w("UpdateViewModel", "已下载记录清理失败：${it.message}") }
                }
            } catch (e: Exception) {
                if (downloadGen == downloadGeneration) {
                    _uiState.update {
                        it.copy(
                            downloading = false,
                            downloadedFilePath = null,
                            // 第 6 轮 P2-2：版本标记与路径同步清掉。
                            downloadedVersion = null,
                            error = "下载失败：${e.message ?: "网络错误"}",
                        )
                    }
                    // 第 7 轮 P2-5：内存路径清掉时持久化记录同步清掉。
                    // B11-P2-1：同上，catch 分支里的附属写失败只记日志、不崩溃。
                    runCatching { downloadedApkStore.clear() }
                        .onFailure { AppLog.w("UpdateViewModel", "已下载记录清理失败：${it.message}") }
                }
            } finally {
                if (downloadGen == downloadGeneration) downloadJob = null
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
        // 第 7 轮 P2-4：“跳过”是明确拒绝，不是“稍后”——进行中的下载
        // 一并取消（代次守卫仍在：取消后旧任务的完成分支不再写状态；
        // 万一完成分支已跑完、取消没拦住，下面的 shouldNotifyDownloadComplete
        // 复核跳过版本后也不弹完成 Toast、不置路径）。
        // 注意 cancelDownload() 不加代次：CE catch 凭现有代次把 downloading
        // 清掉；若在这里 +1 代次，旧任务收尾会被守卫拦住、downloading 永远 true。
        cancelDownload()
        viewModelScope.launch {
            try {
                settingsRepository.setUpdateSkipVersion(latest)
                _uiState.update {
                    it.copy(showUpdateCard = false, toast = nextToastEvent("已跳过 v$latest"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // B8-P2-2：记跳过失败不静默吞掉、也不关卡——卡片留着，用户可再点一次。
                _uiState.update { it.copy(toast = nextToastEvent("跳过失败，请重试")) }
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
 * 第 7 轮 P2-4：下载完成时是否发“下载完成，可以安装了”Toast 并置已下载路径——
 * 该版本已被用户“跳过此版”时不发（跳过与完成竞态时完成分支凭此复核）。
 * 纯逻辑，可单测。
 */
internal fun shouldNotifyDownloadComplete(latestVersion: String, skippedVersion: String?): Boolean =
    !UpdateService.isSkippedVersion(latestVersion, skippedVersion.orEmpty())

/**
 * 第 6 轮 P2-2：重查时是否保留已下载包——只在已下载包的版本就是本次
 * 查到的新版时保留（文件名按版本固定，同版即同一文件）；版本不同或
 * 从未下载过时清掉重下。文件是否还在由调用方另行核对（exists）。
 * 纯逻辑，可单测。
 */
fun shouldKeepDownloadedFile(downloadedVersion: String?, latestVersion: String): Boolean =
    downloadedVersion != null && downloadedVersion == latestVersion

/**
 * P2-22：版本号先去 "v"/"V" 前缀再比较/拼下载地址。
 * B4-P2-13：AggApi.version() 内部已 strip v 前缀（根治），这里的 strip
 * 是死防御，留着无害；第 5 轮起根治点在 AggApi（先 strip 再校验），这里只留兼容委托。
 * 纯逻辑，可单测。
 */
fun stripVersionPrefix(version: String): String = VersionUtils.stripVersionPrefix(version)

/**
 * P2-4（第 5 轮）：检查更新失败的文案分流（纯逻辑，可单测）。
 * 第 6 轮修正注释（原注释已过时）：AggApi.fetchVersion 已先
 * stripVersionPrefix 再 isValidVersion（数据层 D-P2-4 根治已落地，
 * 主备两端点共用同一函数），带 v 前缀的版本号不再到这里才抛错；
 * 本分支如今只对真正非法的版本号生效（仍有价值）。UpdateViewModel 侧的
 * stripVersionPrefix 是死防御，留着无害。
 */
fun versionCheckError(e: Exception): String = when {
    e is AggInvalidResponseException && e.message?.contains("版本号格式非法") == true ->
        "检查更新失败：服务端版本号格式异常，请稍后重试"
    else -> "检查更新失败：${e.message ?: "网络错误"}，请检查网络后重试"
}

/**
 * B12-P2-7：更新检查失败的 error 落点（可单测）——静默检查失败不写 error
 * （设置页只要 error != null 就在底部渲染红字，且没有任何路径清 error，
 * 静默失败会让红字长期挂着）；手动检查失败才展示。
 */
internal fun updateCheckErrorOrNull(silent: Boolean, error: String): String? =
    if (silent) null else error

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
    /** 第 6 轮 P2-2：已下载包对应的版本号（与 downloadedFilePath 同步置/清；重查时按版本比对而非一律清掉）。 */
    val downloadedVersion: String? = null,
    val error: String? = null,
    /** 一次性提示（Toast，2 秒消失，UI 消费后清掉）。 */
    val toast: ToastEvent? = null,
)
