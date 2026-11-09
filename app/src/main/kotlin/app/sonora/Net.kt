package app.sonora

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int) : IOException("HTTP $code")

object Net {
    const val UA_BROWSER =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun url(base: String, params: Map<String, String>): String {
        val b = base.toHttpUrl().newBuilder()
        for ((k, v) in params) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url).header("User-Agent", "Sonora/1.0 (Android)")
        for ((k, v) in headers) b.header(k, v)
        return b.build()
    }

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        client.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code)
            return r.body?.string().orEmpty()
        }
    }

    fun getBytes(url: String): ByteArray {
        client.newCall(request(url, emptyMap())).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code)
            return r.body?.bytes() ?: ByteArray(0)
        }
    }
}

// ---- helpers de JSON (o org.json devolve "null" como texto para valores nulos)
fun JSONObject.s(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}

fun JSONObject.d(key: String): Double {
    val v = opt(key)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }
}

fun JSONObject.i(key: String): Int = d(key).toInt()
