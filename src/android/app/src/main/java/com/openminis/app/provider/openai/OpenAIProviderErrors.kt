package com.openminis.app.provider.openai

import com.openminis.app.data.model.LLMError
import com.openminis.app.provider.CredentialAcceptance
import com.openminis.app.provider.HttpRetryAfter
import com.openminis.app.provider.safeOptString
import org.json.JSONObject

internal fun OpenAIProvider.mapHttpError(statusCode: Int, body: String, retryAfterHeader: String? = null): LLMError {
    // [T-zen-structured-error] OpenCode Zen answers with a structured body
    // ({"error":{"type":"FreeTierError",…}}) whose type carries semantics
    // the generic HTTP mapping cannot see. Measured 2026-10-05: 9 of 14
    // free-lane ids answer 403 FreeTierError — the upstream (Console) serves
    // those models' free lane only to the official OpenCode client, and the
    // caller's key (public/anonymous) never enters into it. ModelError means
    // the catalogue lists the id but the server refuses to serve it. Both are
    // provider refusals where "Invalid API key" is simply false.
    zenStructuredProviderRefusal(body)?.let { return it }
    if (statusCode == 401 || statusCode == 403) {
        // [T-llm-error-401-model-scope] A gateway fronting an upstream pool
        // answers 401 for failures in the channel IT picked, not for anything
        // wrong with the caller's key. When this same credential was accepted by
        // this same host recently, "Invalid API key" is simply false, and its
        // hint sends the user off to regenerate a key that works. Measured: one
        // relay served 200 for a single model and 401 for the other four — same
        // key, same minute, deterministic per model — while its own /v1/models
        // returned 200 throughout.
        //
        // No acceptance evidence falls through to the historical mapping below,
        // so nothing changes for a credential the app has never seen succeed.
        if (CredentialAcceptance.isAccepted(credentialGateKey)) {
            return LLMError.InvalidApiKey(
                "HTTP $statusCode — the same credential was accepted by this host recently, " +
                    "so the refusal is about this model, not the key",
                credentialAccepted = true,
            )
        }
    }
    if (statusCode == 401) return LLMError.InvalidApiKey()
    if (statusCode == 403) {
        // [T-llm-error-403] OpenRouter / self-hosted gateways answer 403 for
        // "valid key, but this model or region is not allowed on your plan".
        // Folding that into a bare InvalidApiKey() told users to check an API
        // key that is actually fine. The detail drives both the message and
        // the actionableHint's dedicated 403 branch.
        return LLMError.InvalidApiKey(
            "HTTP 403 forbidden — the credential was accepted but this model or region is not permitted on the current plan"
        )
    }
    if (statusCode == 429) return HttpRetryAfter.map429(body, retryAfterHeader)

    val message = try {
        val json = JSONObject(body)
        val error = json.optJSONObject("error")
        val errorMessage = error?.safeOptString("message", "") ?: body
        "[$statusCode] ${maskSecrets(errorMessage)}"
    } catch (_: Exception) {
        "HTTP $statusCode: ${maskSecrets(body.take(500))}"
    }

    // [T-llm-error-413] Payload Too Large is the request-size twin of
    // context_length_exceeded — route it to the actionable class (compact /
    // offload hint) instead of a generic, non-retryable ProviderError.
    if (statusCode == 413) return LLMError.ContextLengthExceeded(message)

    // [T-llm-error-classification] 细分 OpenAI 400 类错误，不再笼统地归 ProviderError。
    val lower = message.lowercase()
    when {
        // "max_tokens too large" 是输出参数超限（max_tokens 请求过大），
        // 不是上下文超长，保持 ProviderError。
        !lower.contains("max_tokens too large") && !lower.contains("max_output_tokens") && (
            lower.contains("context_length_exceeded") || lower.contains("maximum context length") ||
            (statusCode == 400 && (
                lower.contains("too many tokens") ||
                lower.contains("reduce the length") ||
                // [T-llm-error-413-note] Excludes output-parameter errors
                // (max_output_tokens mismatches) — a request-param problem is
                // not the context-window problem this class's hint assumes.
                (lower.contains("token") && (lower.contains("max") || lower.contains("limit") || lower.contains("exceed")))
            ))
        ) -> return LLMError.ContextLengthExceeded(message)
        lower.contains("content_filter") || lower.contains("content_policy") ||
        lower.contains("safety") || statusCode == 422 && lower.contains("content") ->
            return LLMError.ContentFiltered(message)
    }

    val transientCodes = setOf(500, 502, 503, 504, 529)
    if (statusCode in transientCodes) {
        // 503 with permanent failure indicators → ProviderError (trigger group fallback)
        if (statusCode == 503 && HttpRetryAfter.isPermanentCapacityBody(body)) {
            return LLMError.ProviderError(message)
        }
        return LLMError.TransientError(message)
    }
    return LLMError.ProviderError(message)
}

internal fun OpenAIProvider.mapError(error: Throwable): LLMError = mapThrowableToLLMError(error)

/**
 * [T-zen-structured-error] Parses an OpenCode Zen structured refusal body —
 * `{"type":"error","error":{"type":"FreeTierError"|"ModelError","message":…}}` —
 * into a precise [LLMError]; null when the body is not one, so generic OpenAI
 * bodies flow to the historical mapping untouched. The upstream message is
 * user-visible verbatim, so it goes through [maskSecrets] like every other
 * server-provided string in this file.
 */
internal fun zenStructuredProviderRefusal(body: String): LLMError? {
    val json = try {
        JSONObject(body)
    } catch (_: Exception) {
        return null
    }
    if (json.optString("type", "") != "error") return null
    val error = json.optJSONObject("error") ?: return null
    val type = error.optString("type", "")
    val message = error.safeOptString("message", "").orEmpty()
    return when (type) {
        "FreeTierError" -> LLMError.ProviderError(
            "FreeTierError: ${maskSecrets(message.ifBlank { "free tier refused" })} — " +
                "the server serves this model's free lane only to the official OpenCode client; " +
                "your key and plan are not involved. Pick another -free model (e.g. space-bunny-free).",
        )
        "ModelError" -> LLMError.ProviderError(
            "ModelError: ${maskSecrets(message.ifBlank { "model refused by server" })} — " +
                "the server's catalogue lists this id but refuses to serve it.",
        )
        else -> null
    }
}

/** Receiver-free so the unit tests can exercise the mapping directly. */
internal fun mapThrowableToLLMError(error: Throwable): LLMError {
    if (error is LLMError) return error
    // [T-llm-error-tls] TLS / certificate failures are permanent
    // misconfigurations (clock skew, MITM proxy, untrusted CA). They are
    // IOExceptions, so the old mapping filed them under retryable
    // NetworkError and burned the full retry budget on a failure no retry
    // can fix — and then the actionable hint told the user to "check your
    // internet connection".
    if (error is javax.net.ssl.SSLException) {
        return LLMError.ProviderError("TLS error: ${error.message ?: error.javaClass.simpleName}")
    }
    if (error is java.io.IOException) return LLMError.NetworkError(error)
    return LLMError.Unknown(error)
}

/**
 * [T-llm-error-secret-mask] Upstream error bodies occasionally echo parts of
 * the request — and with them key-shaped material. Nothing in
 * [LLMError.message] should ever be able to carry a credential into the chat
 * UI (message is truncated to 160 chars for display, but truncation is not
 * redaction).
 *
 * [T-secret-mask-single-source] The patterns moved to
 * [com.openminis.app.util.SecretMasking] so the context-assembly snapshot can
 * use the same rule. This stays as the local name the call sites and
 * LLMErrorClassificationTest already use.
 */
internal fun maskSecrets(text: String): String =
    com.openminis.app.util.SecretMasking.mask(text)
