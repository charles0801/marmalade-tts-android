package app.marmalade.tts.data.cloud

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMeta
import app.marmalade.tts.data.db.VoiceMetaDao
import app.marmalade.tts.ui.screen.FakeDao
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CloudProviderStore]'s key pin end to end: a provider that exists only in
 * the downloaded list is held to the site its saved key was saved for, and
 * keys saved before that URL was recorded get it on first load (trust on
 * first use). The rule itself is pinned in CloudProvidersTest.
 *
 * Robolectric for the bundled asset + filesDir (SDK 34, as
 * CloudApiViewModelTest); a real file-backed DataStore for the settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudProviderStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val offline = CloudJsonHttp { _, _ -> throw IOException("offline test") }
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        File(context.filesDir, "cloud").deleteRecursively()
        scope = CoroutineScope(Job() + Dispatchers.Default)
        val file = File(tempFolder.newFolder(), "settings.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
        settings = SettingsRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** A downloaded provider list: Venice as bundled plus remote-only "newco" at [newcoUrl]. */
    private fun cacheRemoteList(newcoUrl: String) {
        val newco = """
            {"id": "newco", "displayName": "NewCo", "baseUrl": "$newcoUrl", "keyHint": "",
             "models": [{"id": "m", "sampleRate": 24000, "voices": ["v"]}]}
        """.trimIndent()
        val venice = """
            {"id": "venice", "displayName": "Venice", "baseUrl": "https://api.venice.ai/api/v1",
             "keyHint": "", "models": []}
        """.trimIndent()
        File(context.filesDir, "cloud").apply { mkdirs() }
            .resolve("providers.json")
            .writeText("""{"version": 99, "providers": [$venice, $newco]}""")
    }

    /** A fresh store, so nothing is served from a previous load's cache. */
    private fun newco(): CloudProvider =
        CloudProviderStore(context, offline, FakeDao(emptyList()), settings).providerById("newco")!!

    @Test
    fun `a keyed remote-only provider moved off-site is refused and flagged`() {
        cacheRemoteList("https://tts.newco.example/v1")
        runBlocking { settings.setCloudApiKey("newco", "sk-new", newco().baseUrl) }

        cacheRemoteList("https://evil.example/v1")
        val moved = newco()

        assertEquals("https://tts.newco.example/v1", moved.baseUrl)
        assertTrue(moved.movedOffSite)
    }

    @Test
    fun `a keyed remote-only provider may move within its site`() {
        cacheRemoteList("https://tts.newco.example/v1")
        runBlocking { settings.setCloudApiKey("newco", "sk-new", newco().baseUrl) }

        cacheRemoteList("https://api.newco.example/v2")
        val moved = newco()

        assertEquals("https://api.newco.example/v2", moved.baseUrl)
        assertFalse(moved.movedOffSite)
    }

    @Test
    fun `without a saved key the remote list is taken as-is`() {
        cacheRemoteList("https://tts.newco.example/v1")
        newco()
        cacheRemoteList("https://elsewhere.example/v1")

        val p = newco()

        assertEquals("https://elsewhere.example/v1", p.baseUrl)
        assertFalse(p.movedOffSite)
        assertEquals(emptyMap<String, String>(), runBlocking { settings.cloudApiKeyBaseUrls.first() })
    }

    @Test
    fun `a key saved before URLs were recorded is pinned to its provider's URL on first load`() {
        cacheRemoteList("https://tts.newco.example/v1")
        // A 1.1.0-era key: the key pref alone, no URL.
        runBlocking { dataStore.edit { it[stringPreferencesKey("cloud_api_key_newco")] = "sk-old" } }

        assertEquals("https://tts.newco.example/v1", newco().baseUrl)
        assertEquals(
            mapOf("newco" to "https://tts.newco.example/v1"),
            runBlocking { settings.cloudApiKeyBaseUrls.first() },
        )

        // From then on it is held there like any keyed provider.
        cacheRemoteList("https://evil.example/v1")
        val moved = newco()
        assertEquals("https://tts.newco.example/v1", moved.baseUrl)
        assertTrue(moved.movedOffSite)
    }

    // --- regional providers (Azure) ---

    private fun store(dao: VoiceMetaDao = FakeDao(emptyList()), http: CloudJsonHttp = offline) =
        CloudProviderStore(context, http, dao, settings)

    @Test
    fun `a keyed regional provider's endpoint is the URL its key was saved for`() {
        runBlocking {
            settings.setCloudApiKey("azure", "k", "https://eastus.tts.speech.microsoft.com")
        }
        val azure = store().providerById("azure")!!

        assertTrue(azure.needsRegion)
        assertEquals("https://eastus.tts.speech.microsoft.com", azure.endpoint())
        assertEquals("eastus", AzureSpeech.regionFromBaseUrl(azure.endpoint()!!))
    }

    @Test
    fun `a regional provider without a saved key has no endpoint`() {
        // The bundled template is not a request URL; nothing may be sent
        // until the user supplies a region with the key.
        assertNull(store().providerById("azure")!!.endpoint())
    }

    @Test
    fun `changing the region is visible after the cache is invalidated`() {
        val store = store()
        runBlocking {
            settings.setCloudApiKey("azure", "k", "https://eastus.tts.speech.microsoft.com")
        }
        store.invalidate()
        assertEquals("https://eastus.tts.speech.microsoft.com", store.providerById("azure")!!.endpoint())

        // The store caches providers; CloudApiViewModel invalidates after a
        // save so the new region takes effect.
        runBlocking {
            settings.setCloudApiKey("azure", "k", "https://westeurope.tts.speech.microsoft.com")
        }
        assertEquals("https://eastus.tts.speech.microsoft.com", store.providerById("azure")!!.endpoint())
        store.invalidate()
        assertEquals("https://westeurope.tts.speech.microsoft.com", store.providerById("azure")!!.endpoint())
    }

    @Test
    fun `azure discovery asks its regional list path with its own header`() = runBlocking {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val http = CloudJsonHttp { url, headers ->
            requests += url to headers
            AZURE_VOICE_LIST
        }
        val dao = RecordingDao()
        val store = store(dao = dao, http = http)
        settings.setCloudApiKey("azure", "azure-key", "https://eastus.tts.speech.microsoft.com")
        store.invalidate()

        val result = store.discoverVoices(store.providerById("azure")!!)

        assertTrue(result.isSuccess)
        assertEquals(
            "https://eastus.tts.speech.microsoft.com/cognitiveservices/voices/list",
            requests.single().first,
        )
        assertEquals("azure-key", requests.single().second["Ocp-Apim-Subscription-Key"])
        val jenny = dao.rows.first { it.id.endsWith("en-US-JennyNeural") }
        assertEquals("Jenny", jenny.displayName)
        assertEquals("en-US", jenny.languageCode)
        assertEquals("female", jenny.gender)
        assertEquals(24_000, jenny.sampleRate)
    }

    private companion object {
        val AZURE_VOICE_LIST = """
            [
              {"ShortName": "en-US-JennyNeural", "DisplayName": "Jenny",
               "Locale": "en-US", "Gender": "Female", "Status": "GA"},
              {"ShortName": "de-DE-KatjaNeural", "DisplayName": "Katja",
               "Locale": "de-DE", "Gender": "Female", "Status": "GA"}
            ]
        """.trimIndent()
    }

    /** A DAO that keeps `replaceEngine`'s rows in memory. */
    private class RecordingDao : VoiceMetaDao {
        var rows: List<VoiceMeta> = emptyList()

        override fun getAll(): Flow<List<VoiceMeta>> = flowOf(rows)
        override fun getByEngine(engine: String): Flow<List<VoiceMeta>> =
            flowOf(rows.filter { it.engine == engine })

        override suspend fun findById(id: String): VoiceMeta? = rows.firstOrNull { it.id == id }
        override suspend fun count(): Int = rows.size
        override suspend fun upsert(voice: VoiceMeta) { rows = rows + voice }
        override suspend fun upsertAll(voices: List<VoiceMeta>) { rows = rows + voices }
        override suspend fun deleteByEngine(engine: String) {
            rows = rows.filter { it.engine != engine }
        }

        override suspend fun deleteById(id: String) { rows = rows.filter { it.id != id } }
    }
}
