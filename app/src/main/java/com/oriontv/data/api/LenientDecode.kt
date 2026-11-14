package com.oriontv.data.api

import android.util.Log
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 列表 / 表逐条容错解码：服务端一整页（表）里混进一条脏数据时，
 * 保留可解析条目、记日志，只丢脏条目，不让一条脏数据拖垮整页/整表（第 4 轮 P1-8）。
 */
object LenientDecode {

    /** 逐条解码 JSON 数组，坏条目跳过并记数。 */
    fun <T> decodeList(json: Json, raw: JsonArray, serializer: KSerializer<T>): List<T> {
        val out = ArrayList<T>(raw.size)
        var skipped = 0
        for (element in raw) {
            try {
                out.add(json.decodeFromJsonElement(serializer, element))
            } catch (_: Exception) {
                skipped++
            }
        }
        if (skipped > 0) {
            // B4-P2-21：java.util.logging 在 Android 上无输出，改 android.util.Log。
            Log.w("LenientDecode", "列表解码跳过 $skipped 条损坏条目（共 ${raw.size} 条）")
        }
        return out
    }

    /** 逐条解码 JSON 对象表（收藏 / 播放记录），坏条目跳过并记数。 */
    fun <T> decodeMap(json: Json, raw: JsonObject, serializer: KSerializer<T>): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        var skipped = 0
        for ((key, element) in raw) {
            try {
                out[key] = json.decodeFromJsonElement(serializer, element)
            } catch (_: Exception) {
                skipped++
            }
        }
        if (skipped > 0) {
            // B4-P2-21：同上。
            Log.w("LenientDecode", "表解码跳过 $skipped 条损坏条目（共 ${raw.size} 条）")
        }
        return out
    }
}
