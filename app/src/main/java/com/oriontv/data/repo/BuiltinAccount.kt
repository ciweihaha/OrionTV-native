package com.oriontv.data.repo

/**
 * 内置 MoonTV 账号（第 75 轮）：打开即已登录。
 *
 * 用户已确认：MoonTV（movie.ciweihaha.win）单个管理员账号直接内置进 app，
 * 不用填地址、不用填账号密码。用户名与地址是常量（不敏感）；密码是机密，
 * 只由构建时注入（BuildConfig.MOONTV_BUILTIN_PASSWORD），绝不进仓库、
 * 绝不写进代码注释与日志。密码为空串时内置登录关闭，app 行为与旧版一致
 *（用户自己填地址与账号密码）。
 */
data class BuiltinAccount(
    val username: String,
    val password: String,
    val baseUrl: String,
) {
    /** 密码与地址都非空才算配好；任一为空即内置登录关闭。 */
    val isConfigured: Boolean get() = password.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val USERNAME = "admin"
        const val BASE_URL = "https://movie.ciweihaha.win"

        /** 从构建时注入的密码构造；密码为空即未配置。 */
        fun fromBuildConfig(password: String): BuiltinAccount =
            BuiltinAccount(USERNAME, password, BASE_URL)

        /** 未配置的内置账号（单测默认 / 无密码构建）。 */
        fun unconfigured(): BuiltinAccount = BuiltinAccount("", "", "")
    }
}
