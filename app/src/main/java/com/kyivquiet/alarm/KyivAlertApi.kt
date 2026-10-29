package com.kyivquiet.alarm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Kyiv Digital Open API. Only recognized, explicit 0/1 values are treated as valid. */
object KyivAlertApi {
    const val URL_STATE = "https://kyiv.digital/open-api/air-alert/state"

    // true = alert, false = all clear, null = unknown (network/schema error).
    fun fetch(): Boolean? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(URL_STATE).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "KyivQuietAlarm-7.0")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.length > 100000) return null
            val root = JSONObject(body)
            val candidates = arrayListOf(root)
            listOf("current_state", "currentState", "current", "data", "result").forEach {
                root.optJSONObject(it)?.let { item -> candidates.add(item) }
            }
            for (obj in candidates.toList()) {
                listOf("current_state", "currentState", "current").forEach {
                    obj.optJSONObject(it)?.let { nested -> candidates.add(nested) }
                }
            }
            for (obj in candidates) {
                val raw = obj.opt("state")
                val value = if (raw is JSONObject) raw.opt("state") else raw
                when (value?.toString()?.trim()?.lowercase()) {
                    "1", "true" -> return true
                    "0", "false" -> return false
                }
            }
            null
        } catch (_: Exception) { null }
        finally { conn?.disconnect() }
    }
}
