package com.lobbyism.app.net

import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for a self-hosted Nakama server, using only its public REST
 * API and the HTTP stack built into Android. No SDK, no third-party library.
 *
 * Used endpoints:
 *   POST /v2/account/authenticate/device   -> session token
 *   POST /v2/rpc/{id}                      -> call a server function
 *
 * Blocking: call from a background thread only.
 */
class NakamaClient(
    baseUrl: String,
    private val serverKey: String,
    private val deviceId: String,
    private val timeoutMs: Int,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private var token: String? = null

    /** The server-side user id of this device, known after the first login. */
    var userId: String? = null
        private set

    init {
        require(base.startsWith("https://") || base.startsWith("http://")) {
            "Server-Adresse muss mit http:// oder https:// beginnen"
        }
    }

    /** Calls a server function. The payload and the answer are JSON objects. */
    @Throws(IOException::class)
    fun rpc(id: String, payload: JSONObject): JSONObject {
        if (token == null) authenticate()
        var res = post("/v2/rpc/$id", "Bearer $token", JSONObject.quote(payload.toString()))
        if (res.code == 401) {
            // Session expired: log in again once, then retry.
            authenticate()
            res = post("/v2/rpc/$id", "Bearer $token", JSONObject.quote(payload.toString()))
        }
        if (res.code !in 200..299) throw IOException("RPC $id fehlgeschlagen (HTTP ${res.code}): ${res.body.take(200)}")
        val inner = JSONObject(res.body).optString("payload", "{}")
        return JSONObject(if (inner.isEmpty()) "{}" else inner)
    }

    @Throws(IOException::class)
    private fun authenticate() {
        val basic = Base64.encodeToString("$serverKey:".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject().put("id", deviceId).toString()
        val res = post("/v2/account/authenticate/device?create=true", "Basic $basic", body)
        if (res.code !in 200..299) throw IOException("Anmeldung fehlgeschlagen (HTTP ${res.code}): ${res.body.take(200)}")
        val t = JSONObject(res.body).optString("token")
        if (t.isEmpty()) throw IOException("Anmeldung: Server lieferte kein Token")
        token = t
        userId = userIdFromJwt(t)
    }

    /** Reads the user id ("uid" claim) out of the session token. No verification needed: the server verifies. */
    private fun userIdFromJwt(jwt: String): String? = try {
        val part = jwt.split('.')[1]
        val json = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        JSONObject(json).optString("uid").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private class Response(val code: Int, val body: String)

    @Throws(IOException::class)
    private fun post(path: String, authorization: String, body: String): Response {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Authorization", authorization)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
