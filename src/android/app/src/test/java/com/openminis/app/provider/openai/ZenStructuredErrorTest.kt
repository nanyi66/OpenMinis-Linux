package com.openminis.app.provider.openai

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-structured-error] OpenCode Zen answers with a structured body whose
 * error.type carries semantics the generic HTTP mapping cannot see. Mapping a
 * FreeTierError to "Invalid API key" told the user to check a key that is not
 * involved — the upstream simply serves that model's free lane only to the
 * official OpenCode client (measured 2026-10-05: 9 of 14 free-lane ids).
 */
class ZenStructuredErrorTest {

    @Test
    fun `FreeTierError body maps to a provider refusal, not an invalid key`() {
        val body = """
            {"type":"error","error":{"type":"FreeTierError","message":
            "Error from provider (Console): OpenCode's free tier can only be used from within OpenCode"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("FreeTierError:"))
        assertTrue(msg, msg.contains("only to the official OpenCode client"))
        assertTrue(msg, msg.contains("space-bunny-free"))
    }

    @Test
    fun `ModelError body maps to a catalogue-mismatch refusal`() {
        val body = """
            {"type":"error","error":{"type":"ModelError","message":"Model test is not supported"}}
        """.trimIndent()
        val err = zenStructuredProviderRefusal(body)
        assertTrue(err is com.openminis.app.data.model.LLMError.ProviderError)
        val msg = err!!.message ?: ""
        assertTrue(msg, msg.contains("ModelError:"))
        assertTrue(msg, msg.contains("lists this id but refuses to serve it"))
    }

    @Test
    fun `zen 500 body with generic error type does not match the sniffer`() {
        // {"type":"error","error":{"type":"error",…}} must fall through so the
        // transient-500 mapping (retryable) stays in charge.
        assertNull(
            zenStructuredProviderRefusal(
                """{"type":"error","error":{"type":"error","message":"Internal server error"}}""",
            ),
        )
    }

    @Test
    fun `generic OpenAI error body does not match the sniffer`() {
        assertNull(
            zenStructuredProviderRefusal(
                """{"error":{"message":"The model does not exist","type":"invalid_request_error"}}""",
            ),
        )
    }

    @Test
    fun `non-json body does not match the sniffer`() {
        assertNull(zenStructuredProviderRefusal("gateway timeout"))
        assertNull(zenStructuredProviderRefusal(""))
    }
}
