package com.oriontv.data.api

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户 MoonTV 服务（{baseURL}，设置里填，默认空串）。
 * 全部经 baseURL 拼接；除注明外 GET、15 秒超时、响应体 2MB 上限；
 * 401 时用已保存凭据重登后仅对幂等请求（GET/DELETE/HEAD）重试一次，POST 不自动重试。
 *
 * 骨架阶段：只搭接口形状与 401 续期骨架，具体解析与调用在 M1 补齐。
 * 详情接口 GET /api/detail 是死代码（本 HEAD 无调用方），先不实现，M1 再确认服务端是否仍需保留。
 */
@Singleton
class MoonTvApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * 401 续期回调：由 Auth 层注入（用已保存凭据重登）。
     * 返回 true 表示续期成功，可对幂等请求重试一次。
     */
    var onUnauthorized: (suspend () -> Boolean)? = null

    suspend fun login(baseUrl: String, username: String?, password: String?): Boolean =
        withContext(ioDispatcher) {
            val url = "$baseUrl/api/login"
            val body = kotlinx.serialization.json.buildJsonObject {
                username?.let { put("username", it) }
                password?.let { put("password", it) }
            }.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }

    suspend fun logout(baseUrl: String) {
        withContext(ioDispatcher) {
            val request = Request.Builder().url("$baseUrl/api/logout").post("{}".toRequestBody("application/json".toMediaType())).build()
            runCatching { okHttpClient.newCall(request).execute().close() }
            // finally 里无条件清登录标记（由调用方清 StorageKeys.AUTH_COOKIES）
        }
    }

    suspend fun getServerConfig(baseUrl: String): ServerConfig = withContext(ioDispatcher) {
        val url = "$baseUrl/api/server-config".toHttpUrlOrNull()
            ?: throw IllegalArgumentException("API 地址非法：$baseUrl")
        val request = Request.Builder().url(url).get().build()
        val response = executeWithAuthRetry(request)
        val body = response.body?.string().orEmpty()
        json.decodeFromString<ServerConfig>(body)
    }

    /** 401 续期骨架：401 时调 onUnauthorized 重登，成功且请求幂等则重试一次。 */
    private suspend fun executeWithAuthRetry(request: Request): okhttp3.Response {
        val first = okHttpClient.newCall(request).execute()
        if (first.code != 401) return first
        first.close()
        val refreshed = onUnauthorized?.invoke() ?: false
        if (!refreshed || !ApiClient.isIdempotent(request.method)) {
            throw UnauthorizedException("登录已过期，请重新登录")
        }
        return okHttpClient.newCall(request).execute()
    }
}

class UnauthorizedException(message: String) : Exception(message)
