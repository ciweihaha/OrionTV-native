package com.oriontv.feature.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * iQIYI 联想建议（Jewel 第 9 轮 P2-6）——TV v5.9.6 的 getSuggest → iQIYI 同款链：
 * `GET https://suggest.video.iqiyi.com/?if=mobile&key=<拼音或文本>`，响应
 * `{"data":[{"title"/"name": "..."}]}`（见 TV 的 Word.objectFrom）。
 *
 * 屏上键盘敲出的是拼音字母（无中文输入法），中文候选全部来自这条联想接口，
 * 不自造词典：拼音字母先由 [PinyinTable.segmentLetters] 切成音节、确认像拼音
 * （[PinyinTable.isPinyinLike]），再把拼音串发给 iQIYI 出中文候选；历史联想
 * 仍在前、iQIYI 候选补在后（见 [mergeSuggestions]）。
 *
 * 联想是纯辅助：任何失败（无网、超时、非 200、JSON 损坏）都静默返回空列表，
 * 只留历史联想，不弹错、不影响搜索本身。
 */

/** iQIYI 联想接口（TV SearchActivity/SearchFragment 同款地址与参数）。 */
internal fun iqiyiSuggestUrl(query: String): String =
    "https://suggest.video.iqiyi.com/?if=mobile&key=${URLEncoder.encode(query, "UTF-8")}"

/** 联想响应体上限：建议 JSON 只有几 KB，超限直接丢弃、不整包进内存。 */
internal const val IQIYI_SUGGEST_MAX_BODY_BYTES = 64 * 1024

/** 联想请求超时（秒）：辅助请求不等太久，超时即放弃、只留历史联想。 */
internal const val IQIYI_SUGGEST_TIMEOUT_SECONDS = 5L

private val SUGGEST_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * 解析 iQIYI 联想响应（纯函数，可单测）：取 `data[]` 每项的 `title`（无则
 * `name`），去空白、去重，最多 [max] 条；体不是合法 JSON 或无 data 数组时
 * 返回空列表，不抛错。
 */
internal fun parseIqiyiSuggestTitles(
    body: String,
    max: Int = 5,
    json: Json = SUGGEST_JSON,
): List<String> {
    if (body.isBlank()) return emptyList()
    val data = runCatching {
        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
    }.getOrNull() ?: return emptyList()
    val titles = ArrayList<String>()
    for (element in data) {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: continue
        val title = obj.stringField("title") ?: obj.stringField("name") ?: continue
        if (title.isBlank()) continue
        if (titles.none { it == title }) titles.add(title)
        if (titles.size >= max) break
    }
    return titles
}

/** 取 JSON 对象里的字符串字段（缺失/非字符串/null 时返回 null，不抛错）。 */
private fun JsonObject.stringField(name: String): String? {
    val primitive = runCatching { this[name]?.jsonPrimitive }.getOrNull() ?: return null
    return primitive.takeIf { it.isString }?.content
}

/**
 * 合并联想（纯函数，可单测）：历史联想在前、iQIYI 候选补在后，去重后最多
 * [max] 条；历史为空时 iQIYI 候选顶上，iQIYI 为空时只剩历史。
 */
internal fun mergeSuggestions(
    historySuggestions: List<String>,
    iqiyiSuggestions: List<String>,
    max: Int = 5,
): List<String> {
    val merged = ArrayList<String>(max)
    for (item in historySuggestions + iqiyiSuggestions) {
        if (merged.none { it == item }) merged.add(item)
        if (merged.size >= max) break
    }
    return merged
}

/** 联想专用的短超时客户端（复用注入客户端的连接池与调度器，只收紧超时）。 */
internal fun suggestTimeoutClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder()
        .connectTimeout(IQIYI_SUGGEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(IQIYI_SUGGEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(IQIYI_SUGGEST_TIMEOUT_SECONDS + 3L, TimeUnit.SECONDS)
        .build()

/**
 * 可取消的同步执行（ApiClient.executeCancellable 的同款模式，那个是 data.api
 * 的 internal、feature 层调不到）：协程取消时调 `Call.cancel()`，在途请求
 * 立即停；取消导致的 IOException 转成 CancellationException。
 */
internal suspend fun Call.executeCancellableSuggest(): Response =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        try {
            val response = execute()
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        } catch (e: Exception) {
            if (cont.isCancelled) {
                cont.resumeWithException(
                    e as? CancellationException
                        ?: CancellationException("联想请求已取消").apply { initCause(e) },
                )
            } else {
                cont.resumeWithException(e)
            }
        }
    }

/**
 * 拉一条 iQIYI 联想（最佳努力）：非 200、体超限、JSON 损坏、网络错一律返回
 * 空列表；只有真取消（[CancellationException]）原样抛出。网络在 IO 线程跑，
 * 不占主线程。
 */
internal suspend fun fetchIqiyiSuggestTitles(
    client: OkHttpClient,
    query: String,
): List<String> {
    if (query.isBlank()) return emptyList()
    val request = Request.Builder().url(iqiyiSuggestUrl(query)).get().build()
    return try {
        withContext(Dispatchers.IO) {
            client.newCall(request).executeCancellableSuggest().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body
                // 限量读体：超限直接丢弃，不整包进内存。
                val bytes = body.byteStream().use { input ->
                    val out = ByteArray(IQIYI_SUGGEST_MAX_BODY_BYTES + 1)
                    var read = 0
                    while (read <= IQIYI_SUGGEST_MAX_BODY_BYTES) {
                        val n = input.read(out, read, out.size - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read > IQIYI_SUGGEST_MAX_BODY_BYTES) return@withContext emptyList()
                    out.copyOf(read)
                }
                parseIqiyiSuggestTitles(String(bytes, Charsets.UTF_8))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }
}
