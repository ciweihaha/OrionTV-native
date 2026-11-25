package com.oriontv.feature.search

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

/**
 * 系统输入法（百度输入法电视版）引导判定——2026-11-25 用户定：影视 app 与
 * OrionTV 重写版统一用电视上的系统输入法（当贝市场装的百度输入法电视版），
 * 自带九键/全键键盘停用。搜索框获焦即弹系统输入法；系统输入法没装、或装了
 * 没设成默认时，在搜索页给个引导，别悄悄把自带键盘带回来。
 *
 * 判定只看两样东西：已启用的输入法列表、当前默认输入法 id。
 * 百度输入法的包名含 "baidu"（com.baidu.input 等），按子串认，不写死完整包名。
 */
enum class ImeGuidance {
    /** 不用引导：有可用输入法，且（百度是默认，或本机压根没装百度、用别的输入法）。 */
    NONE,

    /** 百度输入法电视版已启用、但当前默认不是它：提示去设置里切换成默认。 */
    BAIDU_NOT_DEFAULT,

    /** 一个启用的输入法都没有：提示去当贝市场装百度输入法电视版。 */
    NO_IME,
}

/** 纯逻辑判定（可单测，不碰 Android 框架）。 */
internal fun decideImeGuidance(
    enabledImeIds: List<String>,
    defaultImeId: String?,
): ImeGuidance {
    if (enabledImeIds.isEmpty()) return ImeGuidance.NO_IME
    val baiduEnabled = enabledImeIds.any { it.contains("baidu", ignoreCase = true) }
    val baiduIsDefault = defaultImeId != null && defaultImeId.contains("baidu", ignoreCase = true)
    if (baiduEnabled && !baiduIsDefault) return ImeGuidance.BAIDU_NOT_DEFAULT
    return ImeGuidance.NONE
}

/** 读本机输入法状态，返回引导判定；读不到时按“不用引导”处理，不挡正常输入。 */
fun readImeGuidance(context: Context): ImeGuidance {
    return try {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return ImeGuidance.NONE
        // 第 84 轮：必须读“已启用”列表——inputMethodList 是“已安装”列表
        //（getInputMethodList），装了没启用的输入法也在里面；按它判定会让
        // “一个启用的输入法都没有”的 NO_IME 永远触发不了（键盘弹不出却
        // 没有任何引导），也会把“百度装了没启用”误报成“已启用没设默认”。
        // 读失败直接按“不用引导”返回，不用空列表冒充 NO_IME。
        val enabled = try {
            imm.enabledInputMethodList.map { it.id }
        } catch (_: Exception) {
            return ImeGuidance.NONE
        }
        val def = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (_: Exception) {
            null
        }
        decideImeGuidance(enabled, def)
    } catch (_: Exception) {
        ImeGuidance.NONE
    }
}

/** 打开系统输入法设置（用户在里面启用/切换默认输入法）。 */
fun openImeSettings(context: Context) {
    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        // 没装设置入口的极端情况：静默失败，不弹错、不挡输入。
    }
}
