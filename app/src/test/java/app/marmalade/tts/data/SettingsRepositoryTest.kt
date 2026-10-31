package app.marmalade.tts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import app.marmalade.tts.lang.UtteranceLanguage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Round-trips the default voice ID through a real (file-backed)
 * preference DataStore.
 *
 * Why a real DataStore instead of a mock: the repository is a
 * one-line wrapper, and mocking DataStore proves nothing. The
 * useful thing to verify is "set X, observe X comes back" — which
 * is just an integration round-trip. `TemporaryFolder` keeps the
 * file scoped to the test method.
 */
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        scope = CoroutineScope(Job() + Dispatchers.Default)
        dataStoreFile = File(tempFolder.newFolder(), "test_settings.preferences_pb")
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultValueFallsBackToKittenDirectWhenUnset() = runTest {
        val repo = newRepo()
        // Nothing written yet → fallback to the baked default (Kitten Direct),
        // the one engine guaranteed present on a fresh/offline install.
        assertEquals(KittenDirectVoiceCatalog.DEFAULT_VOICE_ID, repo.defaultVoiceId.first())
    }

    @Test
    fun setDefaultVoiceIdRoundTrips() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten-direct-v0_8:Kiki")
        assertEquals("kitten-direct-v0_8:Kiki", repo.defaultVoiceId.first())
    }

    @Test
    fun overwriteReplacesPreviousValue() = runTest {
        val repo = newRepo()
        repo.setDefaultVoiceId("kitten-direct-v0_8:Bella")
        repo.setDefaultVoiceId("kitten-direct-v0_8:Leo")
        assertEquals("kitten-direct-v0_8:Leo", repo.defaultVoiceId.first())
    }

    // -- primaryAliasId -----------------------------------------------------

    @Test
    fun primaryAliasId_defaultsToNullWhenUnset() = runTest {
        val repo = newRepo()
        assertNull(
            "Fresh install should have a null primary alias",
            repo.primaryAliasId.first(),
        )
    }

    @Test
    fun setPrimaryAliasId_roundTripsThroughDataStore() = runTest {
        val repo = newRepo()
        repo.setPrimaryAliasId("narrator")
        assertEquals("narrator", repo.primaryAliasId.first())
    }

    @Test
    fun setPrimaryAliasId_nullClearsThePointer() = runTest {
        val repo = newRepo()
        repo.setPrimaryAliasId("narrator")
        assertEquals("narrator", repo.primaryAliasId.first())

        // Null write must remove the key, not store an empty string —
        // otherwise consumers can't distinguish "user cleared" from
        // "never set".
        repo.setPrimaryAliasId(null)
        assertNull(
            "Writing null should remove the key entirely",
            repo.primaryAliasId.first(),
        )
    }

    // -- advertised default voices (system TTS echo detection) --------------

    @Test
    fun advertisedDefaultVoiceIds_startEmpty() = runTest {
        assertEquals(emptySet<String>(), newRepo().advertisedDefaultVoiceIds.first())
    }

    @Test
    fun recordAdvertisedDefaultVoice_dedupesAndPersists() = runTest {
        val repo = newRepo()
        repo.recordAdvertisedDefaultVoice("kokoro-direct-v1_0:af_bella")
        repo.recordAdvertisedDefaultVoice("kokoro-direct-v1_0:af_bella")
        repo.recordAdvertisedDefaultVoice("kitten-direct-v0_8:Bella")

        assertEquals(
            setOf(
                "kokoro-direct-v1_0:af_bella",
                "kitten-direct-v0_8:Bella",
            ),
            repo.advertisedDefaultVoiceIds.first(),
        )
    }

    // -- reader display settings ---------------------------------------------

    @Test
    fun readerDisplaySettings_areAbsentUntilTheUserChangesThem() = runTest {
        val repo = newRepo()
        // All three defaults are applied by the reader, not seeded here — an
        // unset background is what lets the reader follow the app's theme.
        assertNull(repo.readerBackground.first())
        assertNull(repo.readerFont.first())
        assertNull(repo.readerFontSizeSp.first())
    }

    @Test
    fun readerDisplaySettings_roundTripThroughDataStore() = runTest {
        val repo = newRepo()
        repo.setReaderBackground("Paper")
        repo.setReaderFont("Serif")
        repo.setReaderFontSizeSp(22)

        assertEquals("Paper", repo.readerBackground.first())
        assertEquals("Serif", repo.readerFont.first())
        assertEquals(22, repo.readerFontSizeSp.first())
    }

    private fun newRepo(): SettingsRepository = SettingsRepository(newDataStore())

    private fun newDataStore() = PreferenceDataStoreFactory.create(
        scope = scope,
        produceFile = { dataStoreFile },
    )

    // -- measured engine RTF (#40: dropped after an app or engine update) -----

    @Test
    fun engineRtf_measuredByThisVersionSurvivesAndAverages() = runTest {
        val repo = SettingsRepository(newDataStore(), V1_2)
        repo.recordEngineRtf(POCKET, 1.0)
        repo.recordEngineRtf(POCKET, 2.0)

        assertEquals(mapOf(POCKET to 1.3), repo.engineRtf.first().mapValues { round(it.value) })
    }

    @Test
    fun engineRtf_fromAnOlderVersionCodeIsIgnoredThenCleared() = runTest {
        val ds = newDataStore()
        val old = SettingsRepository(ds, V1_1)
        old.recordEngineRtf(POCKET, 22.0)
        old.recordEngineRtf(KOKORO, 1.1)

        val updated = SettingsRepository(ds, V1_2)
        assertEquals(emptyMap<String, Double>(), updated.engineRtf.first())

        // The first sample after the update starts afresh: Pocket's 22 is not
        // folded in, and Kokoro's stale value is gone rather than revived.
        updated.recordEngineRtf(POCKET, 0.5)
        assertEquals(mapOf(POCKET to 0.5), updated.engineRtf.first())
        assertEquals(setOf(POCKET), rtfKeysOnDisk(ds))
    }

    @Test
    fun engineRtf_storedBeforeVersionStampingIsIgnored() = runTest {
        // A 1.1.0 install stored RTFs with no version stamp at all.
        val ds = newDataStore()
        ds.edit { it[doublePreferencesKey("engine_rtf_$POCKET")] = 22.0 }

        assertEquals(emptyMap<String, Double>(), SettingsRepository(ds, V1_2).engineRtf.first())
    }

    @Test
    fun engineRtf_restartsWhenTheLanguageChanges() = runTest {
        // #54: Kokoro renders Spanish slower than English; a blend of the two
        // flipped the speed warning and chunk mode between reads.
        val repo = SettingsRepository(newDataStore(), V1_2)
        repo.recordEngineRtf(KOKORO, 0.4, "en-us")
        repo.recordEngineRtf(KOKORO, 0.4, "en-us")
        repo.recordEngineRtf(KOKORO, 0.7, "es")
        assertEquals(mapOf(KOKORO to 0.7), repo.engineRtf.first())

        // Same language again: averages as before.
        repo.recordEngineRtf(KOKORO, 0.6, "es")
        assertEquals(mapOf(KOKORO to 0.67), repo.engineRtf.first().mapValues { Math.round(it.value * 100) / 100.0 })

        // The engine's default language (null) is a language of its own.
        repo.recordEngineRtf(KOKORO, 0.3, null)
        assertEquals(mapOf(KOKORO to 0.3), repo.engineRtf.first())
    }

    @Test
    fun engineRtf_headingsWithNoLanguageDoNotRestartIt() = runTest {
        // Short lines and headings resolve to no language (null), the
        // paragraphs around them to the voice's own: the same language.
        val repo = SettingsRepository(newDataStore(), V1_2)
        for ((rtf, detected) in listOf(0.4 to "en-us", 0.5 to null, 0.4 to "en-us", 0.5 to null)) {
            repo.recordEngineRtf(KOKORO, rtf, UtteranceLanguage.renderLanguage(KOKORO, "af_heart", detected))
        }
        // 0.4 → 0.43 → 0.421 → 0.4447: one running average, never restarted.
        assertEquals(0.445, repo.engineRtf.first().getValue(KOKORO), 0.001)
    }

    @Test
    fun clearEngineRtf_dropsThatEngineOnly() = runTest {
        val repo = SettingsRepository(newDataStore(), V1_2)
        repo.recordEngineRtf(POCKET, 0.6)
        repo.recordEngineRtf(KOKORO, 0.9)

        repo.clearEngineRtf(POCKET)

        assertEquals(mapOf(KOKORO to 0.9), repo.engineRtf.first())
    }

    private suspend fun rtfKeysOnDisk(ds: DataStore<Preferences>): Set<String> =
        ds.data.first().asMap().filterValues { it is Double }.keys
            .map { it.name }
            .filter { it.startsWith("engine_rtf_") }
            .map { it.removePrefix("engine_rtf_") }
            .toSet()

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0

    private companion object {
        const val V1_1 = 10010000
        const val V1_2 = 10020000
        const val POCKET = "pocket-tts-en-v2026_04"
        const val KOKORO = "kokoro-direct-v1_0"
    }

    // -- latency samples + weekly quota ---------------------------------------

    @Test
    fun latencySamples_rollOffOldestBeyondTheWindow() = runTest {
        val repo = newRepo()
        for (ms in 1..5) repo.recordLatencySample("venice:m", millis = ms * 100, keep = 3)
        assertEquals(listOf(300, 400, 500), repo.latencySamples.first().getValue("venice:m"))
    }

    @Test
    fun latencySamples_ignoreTheQuotaKeys() = runTest {
        // The quota prefix deliberately doesn't nest under the sample
        // prefix; if it ever did, this would surface it as a phantom model.
        val repo = newRepo()
        repo.claimLatencyQuota("venice:m", week = 1L, perWeek = 3)
        assertEquals(emptyMap<String, List<Int>>(), repo.latencySamples.first())
    }

    @Test
    fun cloudDisclaimer_defaultsToUnacceptedAndIsOneWay() = runTest {
        val repo = newRepo()
        // The gate fails open if this ever defaults true — a fresh install
        // would reach the key field having been told nothing.
        assertFalse(repo.cloudDisclaimerAccepted.first())

        repo.acceptCloudDisclaimer()
        assertTrue(repo.cloudDisclaimerAccepted.first())

        // Removing every key must not re-arm the gate. Acceptance records
        // "was told", not "is using"; re-prompting a user who already read
        // it is how people learn to dismiss disclaimers unread.
        repo.setCloudApiKey("venice", "sk-test", "https://api.venice.ai/api/v1")
        repo.setCloudApiKey("venice", "", "https://api.venice.ai/api/v1")
        assertTrue(repo.cloudDisclaimerAccepted.first())
    }

    @Test
    fun cloudApiKey_recordsTheUrlItWasSavedForAndRemovesItWithTheKey() = runTest {
        val repo = newRepo()
        repo.setCloudApiKey("newco", "sk-new", "https://tts.newco.example/v1")

        assertEquals(mapOf("newco" to "https://tts.newco.example/v1"), repo.cloudApiKeyBaseUrls.first())
        // The URL prefix must not read back as a key of a provider "url_newco".
        assertEquals(mapOf("newco" to "sk-new"), repo.cloudApiKeys.first())

        repo.setCloudApiKey("newco", "", "https://tts.newco.example/v1")
        assertEquals(emptyMap<String, String>(), repo.cloudApiKeyBaseUrls.first())
        assertEquals(emptyMap<String, String>(), repo.cloudApiKeys.first())
    }

    @Test
    fun recordCloudApiKeyBaseUrls_fillsGapsButNeverOverwrites() = runTest {
        val repo = newRepo()
        repo.setCloudApiKey("newco", "sk-new", "https://tts.newco.example/v1")

        repo.recordCloudApiKeyBaseUrls(
            mapOf("newco" to "https://evil.example/v1", "other" to "https://tts.other.example"),
        )

        assertEquals(
            mapOf("newco" to "https://tts.newco.example/v1", "other" to "https://tts.other.example"),
            repo.cloudApiKeyBaseUrls.first(),
        )
    }

    @Test
    fun latencyQuota_grantsExactlyPerWeekThenRefusesUntilTheWeekTurns() = runTest {
        val repo = newRepo()
        assertEquals(
            listOf(true, true, true, false, false),
            (1..5).map { repo.claimLatencyQuota("venice:m", week = 900L, perWeek = 3) },
        )
        // A new week starts a fresh budget...
        assertTrue(repo.claimLatencyQuota("venice:m", week = 901L, perWeek = 3))
        // ...and each model has its own.
        assertTrue(repo.claimLatencyQuota("venice:other", week = 901L, perWeek = 3))
    }
}
