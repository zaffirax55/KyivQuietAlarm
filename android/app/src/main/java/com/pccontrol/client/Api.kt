package com.pccontrol.client

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PcApi(private val ip: String, private val keyHex: String) {
    companion object {
        const val PORT = 43821
        fun validIp(ip: String): Boolean {
            val parts = ip.split('.')
            return parts.size == 4 && parts.all { s -> s.isNotEmpty() && s.length <= 3 && s.all(Char::isDigit) && (s.toIntOrNull()?.let { it in 0..255 } == true) }
        }
        fun validSecret(v: String) = v.length == 64 && v.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i*2,i*2+2).toInt(16).toByte() }
    }
    private fun request(method: String, path: String, textBody: String = ""): JSONObject {
        require(validIp(ip) && validSecret(keyHex))
        val body = textBody.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis().toString()
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }.let(::hex)
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(body))
        val message = "$method\n$path\n$timestamp\n$nonce\n$digest"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(unhex(keyHex), "HmacSHA256"))
        val signature = hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
        val conn = URL("http://$ip:$PORT$path").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.useCaches = false
            conn.setRequestProperty("X-PC-Time", timestamp)
            conn.setRequestProperty("X-PC-Nonce", nonce)
            conn.setRequestProperty("X-PC-Signature", signature)
            conn.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body) }
            }
            val status = conn.responseCode
            val input = if (status in 200..299) conn.inputStream else conn.errorStream
            val data = input?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            val result = JSONObject(data)
            if (status !in 200..299) throw IllegalStateException(result.optString("error", "HTTP $status"))
            result
        } finally { conn.disconnect() }
    }
    fun status() = request("GET", "/v1/status")
    fun action(name: String) = request("POST", "/v1/action", JSONObject().put("action", name).toString())
}
