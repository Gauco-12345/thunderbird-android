package com.fsck.k9.ui.dcmail

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class DcAiException(message: String) : Exception(message)

/**
 * DC Mail: minimal client for the Anthropic Messages API.
 *
 * Must be called from a background thread.
 */
object DcClaudeClient {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_TOKENS = 1500
    private const val HTTP_ERROR_THRESHOLD = 400

    @Throws(DcAiException::class)
    fun complete(config: DcAiConfig, system: String, userMessage: String, maxTokens: Int = MAX_TOKENS): String {
        val body = JSONObject()
            .put("model", config.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", userMessage),
                ),
            )

        val connection = try {
            URL(ENDPOINT).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw DcAiException(e.message ?: e.javaClass.simpleName)
        }

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.setRequestProperty("x-api-key", config.apiKey)
            connection.setRequestProperty("anthropic-version", API_VERSION)

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            val response = readFully(
                if (code >= HTTP_ERROR_THRESHOLD) connection.errorStream else connection.inputStream,
            )

            if (code >= HTTP_ERROR_THRESHOLD) {
                throw DcAiException("HTTP $code: ${extractError(response)}")
            }

            return extractText(response)
        } catch (e: IOException) {
            throw DcAiException(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun readFully(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun extractText(response: String): String {
        try {
            val content = JSONObject(response).getJSONArray("content")
            val text = StringBuilder()
            for (index in 0 until content.length()) {
                val block = content.getJSONObject(index)
                if (block.optString("type") == "text") {
                    text.append(block.optString("text"))
                }
            }
            if (text.isBlank()) throw DcAiException("Leere Antwort")
            return text.toString().trim()
        } catch (e: JSONException) {
            throw DcAiException(e.message ?: "Ungültige Antwort")
        }
    }

    private fun extractError(response: String): String {
        return try {
            JSONObject(response).getJSONObject("error").optString("message", response)
        } catch (e: JSONException) {
            response.take(ERROR_SNIPPET_LENGTH)
        }
    }

    private const val ERROR_SNIPPET_LENGTH = 300
}
