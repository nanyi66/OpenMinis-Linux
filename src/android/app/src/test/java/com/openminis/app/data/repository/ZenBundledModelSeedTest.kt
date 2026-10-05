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
    fun `bundled models all survive the live-refresh free-lane filter`() {
        // fetchModels for Zen instances keeps only big-pickle / *free* ids.
        // If a bundled model id failed that predicate, a later live refresh
        // would visibly REMOVE it right after the seed added it.
        val ids = bundledZenModels().map { it.id }
        assertEquals(4, ids.size)
        for (id in ids) {
            assertTrue(
                "bundled model '$id' would be dropped by the refresh filter",
                id == "big-pickle" || id.contains("-free", ignoreCase = true),
            )
        }
    }
}
