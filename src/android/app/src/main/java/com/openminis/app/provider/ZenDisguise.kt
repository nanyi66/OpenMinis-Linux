package com.openminis.app.provider

import com.openminis.app.data.model.LLMMessage
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

/** OpenCode Zen request identity helpers. */
object ZenDisguise {
    private const val USER_AGENT = "opencode/1.18.31 (Android arm64; native)"
    private const val PROJECT = "prj_4d5348f7f5f7d0b3f4cc7a7e"
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    fun userAgent(): String = USER_AGENT

    fun sessionId(messages: List<LLMMessage>): String {
        val seed = messages.firstOrNull { it.role == LLMMessage.Role.USER }?.content?.toString().orEmpty()
            .ifBlank { "opencode2dsh-empty-conversation" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("ses\u0000" + seed).toByteArray(Charsets.UTF_8))
        val time = digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        var value = BigInteger(1, digest.copyOfRange(6, 16))
        val chars = CharArray(14)
        val base = BigInteger.valueOf(62L)
        for (i in chars.indices.reversed()) {
            chars[i] = ALPHABET[(value % base).toInt()]
            value /= base
        }
        return "ses_$time${chars.concatToString()}"
    }

    fun requestId(): String = "req_" + java.util.UUID.randomUUID().toString().replace("-", "")

    fun applyToBody(builder: Request.Builder, body: String): Request.Builder {
        val messages = runCatching {
            val array = JSONObject(body).optJSONArray("messages") ?: return@runCatching emptyList<LLMMessage>()
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val role = when (item.optString("role")) {
                        "assistant" -> LLMMessage.Role.ASSISTANT
                        else -> LLMMessage.Role.USER
                    }
                    add(LLMMessage(role, item.optString("content")))
                }
            }
        }.getOrDefault(emptyList())
        return apply(builder, messages)
    }

    fun apply(builder: Request.Builder, messages: List<LLMMessage>): Request.Builder {
        val session = sessionId(messages)
        return builder
            .header("User-Agent", USER_AGENT)
            .header("x-opencode-client", "cli")
            .header("x-opencode-session", session)
            .header("x-session-affinity", session)
            .header("X-Session-Id", session)
            .header("x-opencode-request", requestId())
            .header("x-opencode-project", PROJECT)
    }
}
