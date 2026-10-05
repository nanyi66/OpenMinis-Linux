package com.openminis.app.data.repository

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-zen-bundled-model-patch] A hand-built instance pointing at the
 * bundled Zen endpoint wins the reconciliation skip check ("already represents
 * this service") — which previously meant the bundled free models were never
 * seeded anywhere and a never-refreshed hand-built instance stayed an empty
 * shell: the exact "no free models anywhere" report. The patch fills only the
 * empty case; every other shape must stay untouched.
 */
class ZenBundledModelSeedTest {

    private fun zenInstance(baseURL: String? = ZEN_BUNDLED_ENDPOINT) = ProviderInstance(
        id = "hand-built-zen",
        label = "Opencode",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        isEnabled = true,
        customBaseURL = baseURL,
        appendV1Suffix = false,
    )

    private fun entry(instanceId: String, modelId: String) = ModelEntry(
        providerInstanceId = instanceId,
        baseModel = LLMModel(modelId, modelId, "OpenCode Zen"),
    )

    @Test
    fun `empty hand-built zen instance needs the bundled models`() {
        assertTrue(zenInstanceNeedsBundledModels(zenInstance(), emptyList()))
    }

    @Test
    fun `zen instance with any own entry does not get re-seeded`() {
        assertFalse(
            zenInstanceNeedsBundledModels(
                zenInstance(),
                listOf(entry("hand-built-zen", "some-live-model")),
            ),
        )
    }

    @Test
    fun `entries belonging to other instances do not block the seed`() {
        // A foreign entry under a different instance id must not hide the
        // fact that THIS instance shows zero models.
        assertTrue(
            zenInstanceNeedsBundledModels(
                zenInstance(),
                listOf(entry("other-instance", "some-live-model")),
            ),
        )
    }

    @Test
    fun `trailing slash on the hand-built endpoint still matches`() {
        // isZenInstance matches with trimEnd('/'); the patch predicate must
        // agree, otherwise the seed silently disagrees with the skip check.
        assertTrue(
            zenInstanceNeedsBundledModels(
                zenInstance("$ZEN_BUNDLED_ENDPOINT/"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `non-zen endpoint never gets the bundled models`() {
        assertFalse(
            zenInstanceNeedsBundledModels(
                zenInstance("https://example.com/v1"),
                emptyList(),
            ),
        )
    }

    @Test
    fun `null endpoint never gets the bundled models`() {
        assertFalse(zenInstanceNeedsBundledModels(zenInstance(null), emptyList()))
    }

    @Test
    fun `bundled models all survive the live-refresh filter`() {
        // If a bundled model id failed zenVisibleModels, a later live refresh
        // would visibly REMOVE it right after the seed added it.
        val ids = bundledZenModels().map { it.id }
        for (id in ids) {
            assertTrue(
                "bundled model '$id' would be dropped by the refresh filter",
                zenVisibleModels(listOf(LLMModel(id, id, "OpenCode Zen"))).isNotEmpty(),
            )
        }
    }

    /** [T-zen-usable-free-lane] The visible list IS the usable list. */
    @Test
    fun `zenVisibleModels drops free-lane ids the upstream refuses`() {
        // Live-measured 2026-10-05: these ids answer 403 FreeTierError /
        // RegionError / 500 on the first call — showing them is what made the
        // user pick a model that can never work.
        val dead = listOf(
            "big-pickle", "ling-3.1-flash-free", "fledge-alpha-free",
            "mimo-v2.5-free", "mimo-v2.6-flash-free", "longcat-2.5-preview-free",
            "ling-3.0-flash-fin-free", "nemotron-3-ultra-free",
            "nemotron-3.5-lightning-free", "muse-spark-1.3-contributor-free",
            "jev-1.13-free", "deepseek-v4-flash-free",
        )
        val catalog = dead.map { LLMModel(it, it, "OpenCode Zen") } + bundledZenModels()
        val visible = zenVisibleModels(catalog)
        assertEquals(bundledZenModels().map { it.id }, visible.map { it.id })
    }

    @Test
    fun `zenVisibleModels drops paid-lane rows a keyless instance cannot drive`() {
        val catalog = listOf(
            LLMModel("claude-something", "Claude", "OpenCode Zen"),
            LLMModel("gpt-something", "GPT", "OpenCode Zen"),
        ) + bundledZenModels()
        assertEquals(bundledZenModels().map { it.id }, zenVisibleModels(catalog).map { it.id })
    }

    @Test
    fun `zenStaleEntryIds marks only this instance's entries outside the usable set`() {
        val entries = listOf(
            entry("zen-1", "big-pickle"),          // 403 FreeTierError — dead
            entry("zen-1", "space-bunny-free"),    // usable — must survive
            entry("zen-1", "claude-x"),            // paid lane — dead for keyless
            entry("other-1", "big-pickle"),        // different instance — untouchable
        )
        val stale = zenStaleEntryIds("zen-1", entries)
        assertEquals(setOf(entries[0].id, entries[2].id), stale)
    }
}
