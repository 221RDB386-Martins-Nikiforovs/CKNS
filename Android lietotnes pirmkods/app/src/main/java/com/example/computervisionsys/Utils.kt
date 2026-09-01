package com.example.ckns

import android.content.Context
import java.io.File
import java.nio.charset.Charset
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.iterator

class AssetUtils(private val ctx: Context) {
    fun assetFilePath(assetName: String): String {
        val out = File(ctx.filesDir, assetName)
        if (!out.exists() || out.length() == 0L) {
            ctx.assets.open(assetName).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out.absolutePath
    }
}

fun loadClassNamesFromAssets(ctx: Context, fileName: String = "class_names.json"): List<String> {
    return try {
        ctx.assets.open(fileName).use { input ->
            val text = input.readBytes().toString(Charset.forName("UTF-8")).trim()

            if (text.startsWith("[")) {
                val arr = JSONArray(text)
                buildList(arr.length()) {
                    for (i in 0 until arr.length()) {
                        add(arr.optString(i, i.toString()))
                    }
                }
            } else {
                val obj = JSONObject(text)
                val keys = obj.keys()
                var maxId = 0
                val tmp = HashMap<Int, String>()

                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toIntOrNull() ?: continue
                    val name = obj.optString(k, id.toString())
                    tmp[id] = name
                    if (id > maxId) maxId = id
                }

                if (maxId <= 0) {
                    emptyList()
                } else {
                    MutableList(maxId + 1) { "?" }.apply {
                        for ((id, name) in tmp) {
                            if (id in indices) this[id] = name
                        }
                    }
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}