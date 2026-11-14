package com.oriontv.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oriontv.core.storage.StorageKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登录凭据安全存储（plan §5.4 mytv_login_credentials）：
 * EncryptedSharedPreferences + Keystore，绝不明文落盘；读/写失败 rethrow。
 * 真会话 cookie 在 OkHttp CookieJar 里，这里只存账号密码（供自动续期用）。
 */
@Singleton
class CredentialsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            StorageKeys.LOGIN_CREDENTIALS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 第 3 轮收尾 B-P2-18：三个方法均为 suspend 并切 IO dispatcher。
     * EncryptedSharedPreferences 首次访问含 Keystore/磁盘 IO，之前在主线程
     * 协程上下文（AuthViewModel、OrionNavHost 的 LaunchedEffect）里同步调。
     */
    suspend fun save(username: String, password: String) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    /** 返回已保存的 (账号, 密码)，无保存时返回 null。 */
    suspend fun load(): Pair<String, String>? = withContext(Dispatchers.IO) {
        val username = prefs.getString("username", null) ?: return@withContext null
        val password = prefs.getString("password", null) ?: return@withContext null
        username to password
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().clear().apply()
    }
}
