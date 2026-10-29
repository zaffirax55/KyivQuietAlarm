package com.kyivquiet.alarm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL

data class KyivAlertResult(val active: Boolean?, val detail: String)

/**
 * The Kyiv Digital current state API. Unknown does not mean all-clear.
 */
object KyivAlertApi {
    const val URL_STATE = "https://kyiv.digital/open-api/air-alert/state"

    fun fetch(): Boolean? = fetchDetailed().active

    fun fetchDetailed(): KyivAlertResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(URL_STATE).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 7500
            conn.readTimeout = 7500
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("Pragma", "no-cache")
            conn.setRequestProperty("User-Agent", "TyhoKyivAlarm-9.0")
            val code = conn.responseCode
            if (code != 200) return KyivAlertResult(null, "API вернул HTTP $code")
            val contentType = conn.contentType ?: ""
            if (!contentType.contains("json", ignoreCase = true)) {
                return KyivAlertResult(null, "Неожиданный тип ответа API")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.length !in 2..100_000) return KyivAlertResult(null, "Некорректная длина ответа")
            val root = JSONObject(body)
            val candidates = arrayListOf(root)
            listOf("current", "current_state", "currentState", "data", "result").forEach {
                root.optJSONObject(it)?.let { nested -> candidates.add(nested) }
            }
            for (obj in candidates) {
                val raw = obj.opt("state")
                val v = if (raw is JSONObject) raw.opt("state") else raw
                when (v?.toString()?.trim()?.lowercase()) {
                    "1", "true" -> return KyivAlertResult(true, "Київ Цифровий: тревога активна")
                    "0", "false" -> return KyivAlertResult(false, "Київ Цифровий: отбой подтверждён")
                }
            }
            KyivAlertResult(null, "API изменил формат ответа")
        } catch (_: SocketTimeoutException) {
            KyivAlertResult(null, "Истекло время ожидания сервера")
        } catch (_: UnknownHostException) {
            KyivAlertResult(null, "Нет доступа к серверу")
        } catch (_: Exception) {
            KyivAlertResult(null, "Не удалось проверить API")
        } finally {
            conn?.disconnect()
        }
    }
}
