package app.marmalade.tts.data.cloud

import android.content.Context
import android.util.Log
import app.marmalade.tts.data.CloudApiVoiceCatalog
import app.marmalade.tts.data.SettingsRepository
import app.marmalade.tts.data.db.VoiceMetaDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONException

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   providers()  ◄── memory cache
//     │              ◄── filesDir/cloud/providers.json   (remote copy, if fetched)
//     │              ◄── assets/cloud-providers.json     (bundled fallback; also
//     │                   pins each built-in provider's baseUrl to its site)
//     │              ◄── SettingsRepository.cloudApiKeyBaseUrls (pins each
//     │                   keyed provider to the site its key was saved for;
//     │                   for a regional provider this URL also supplies the
//     │                   active endpoint — CloudProvider.resolvedBaseUrl)
//     │              + per-provider discovery overlay
//     │                 (filesDir/cloud/voices-<id>.json, written by
//     │                  discoverVoices() from the provider's own list path:
//     │                   GET {baseUrl}/models?type=tts    (OpenAI-shaped)
//     │                   GET {endpoint}/cognitiveservices/voices/list (Azure))
//     ▼
//   sync() ──► VoiceMeta rows for every keyed provider's models × voices
//              ──► voiceDao.replaceEngine("cloud-api-v1", rows)
//
//   Update paths (all data, no app release):
//     - provider added/changed  → refreshProviders() pulls the remote JSON
//     - provider catalog drift  → discoverVoices() re-queries the list path
// -----------------------------------------------------------------------------

/**
 * Source of truth for cloud TTS providers and the Room rows of the Cloud
 * API engine.
 *
 * Providers are *data*: a bundled `cloud-providers.json` asset, optionally
 * superseded by the same file fetched from the engines repo
 * ([REMOTE_PROVIDERS_URL]) — so supporting a new provider, or Venice
 * reshuffling its catalog, never requires an app update. Voice lists for
 * providers that support it come from live discovery against the
 * provider's list path ([CloudApiStyle]); both fetches are cached under
 * `filesDir/cloud/` so the app works offline with the last-known state.
 *
 * The remote list is trusted input with a limit: it may add providers and
 * change a built-in provider's models, but may only move a built-in
 * provider's `baseUrl` (which receives the user's saved API key) within
 * that provider's own site — [CloudProviders.pinBuiltInSites] — and any
 * provider the user saved a key for within the site of the URL the key was
 * saved for — [CloudProviders.pinKeyedSites]. For a regional provider that
 * saved URL is also the active endpoint: the user picks the region with
 * the key, and it is stored in the same place — see [CloudProvider.endpoint].
 * Process
 * rule (Max): any change to `cloud-providers.json` in the engines repo
 * needs Max's manual review; agents never merge it.
 */
@Singleton
class CloudProviderStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: CloudJsonHttp,
    private val voiceDao: VoiceMetaDao,
    private val settings: SettingsRepository,
) : CloudProviderDirectory {

    private val cloudDir: File
        get() = File(context.filesDir, "cloud").apply { mkdirs() }

    private val providersCacheFile: File get() = File(cloudDir, "providers.json")
    private fun voicesCacheFile(providerId: String): File =
        File(cloudDir, "voices-$providerId.json")

    /** Merged descriptor+discovery view; invalidated by refresh/discovery. */
    @Volatile
    private var cached: List<CloudProvider>? = null

    /**
     * The provider list with each discovering provider's model list
     * replaced by its cached live-discovery result (when one exists).
     * Cheap after the first call; the first call reads two small local
     * files. Never touches the network.
     */
    fun providers(): List<CloudProvider> {
        cached?.let { return it }
        val base = loadBaseProviders()
        val merged = base.map { provider ->
            if (!provider.discoverVoices) return@map provider
            val cache = voicesCacheFile(provider.id)
            if (!cache.exists()) return@map provider
            val discovered = runCatching {
                CloudProviders.parseDiscovery(provider, cache.readText())
            }.getOrElse { emptyList() }
            if (discovered.isEmpty()) {
                provider
            } else {
                // Join, never replace. The descriptor's model list is the
                // allowlist and the only source of capability data; a
                // wholesale copy(models = discovered) would silently drop
                // every sampleRate and re-open the fail-open hole.
                provider.copy(models = CloudProviders.mergeDiscovered(provider.models, discovered))
            }
        }
        cached = merged
        return merged
    }

    override fun providerById(id: String): CloudProvider? =
        providers().firstOrNull { it.id == id }

    /**
     * Drop the merged descriptor cache. Call after changing anything
     * [providers] derives from settings (a key or its recorded URL, which is
     * where a regional provider's endpoint comes from), so the next
     * [providers] sees it.
     */
    fun invalidate() {
        cached = null
    }

    /**
     * Fetch the provider list from the engines repo and cache it. Quietly
     * a no-op on any network/parse failure — the bundled (or previously
     * cached) descriptors stay in force. Returns true when a valid new
     * copy was stored.
     */
    suspend fun refreshProviders(): Boolean = withContext(Dispatchers.IO) {
        val body = try {
            http.get(REMOTE_PROVIDERS_URL, emptyMap())
        } catch (e: IOException) {
            Log.i(TAG, "provider list refresh skipped: ${e.message}")
            return@withContext false
        } catch (e: SecurityException) {
            // Revoked INTERNET permission (GrapheneOS Network toggle) throws
            // unchecked from the socket layer; same quiet no-op as offline.
            Log.i(TAG, "provider list refresh skipped: ${e.message}")
            return@withContext false
        }
        try {
            CloudProviders.parse(body) // validate before persisting
        } catch (e: JSONException) {
            Log.w(TAG, "remote provider list malformed; keeping current", e)
            return@withContext false
        }
        providersCacheFile.writeText(body)
        cached = null
        sync()
        true
    }

    /**
     * Query [provider]'s live model+voice list and cache it, then re-sync
     * the Room rows. The key is optional for an OpenAI-shaped provider
     * (Venice serves the model list unauthenticated) and required by Azure;
     * it is sent when configured, in that provider's own header.
     */
    suspend fun discoverVoices(provider: CloudProvider): Result<List<CloudModel>> =
        withContext(Dispatchers.IO) {
            val endpoint = provider.endpoint()
                ?: return@withContext Result.failure(
                    IOException("${provider.displayName}: set a region with the API key"),
                )
            val key = settings.cloudApiKeyFor(provider.id).first().ifBlank { null }
            val url: String
            val headers: Map<String, String>
            when (provider.api) {
                CloudApiStyle.OPENAI -> {
                    url = "$endpoint/models?type=tts"
                    headers = key?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
                }
                CloudApiStyle.AZURE -> {
                    url = "$endpoint/cognitiveservices/voices/list"
                    headers = key?.let { mapOf("Ocp-Apim-Subscription-Key" to it) } ?: emptyMap()
                }
            }
            val body = try {
                http.get(url, headers)
            } catch (e: IOException) {
                return@withContext Result.failure(e)
            } catch (e: SecurityException) {
                return@withContext Result.failure(e)
            }
            val discovered = try {
                CloudProviders.parseDiscovery(provider, body)
            } catch (e: JSONException) {
                return@withContext Result.failure(e)
            }
            // Report what the user will actually be able to speak with, not
            // everything the provider advertises — Venice lists 11 TTS models
            // and only the allowlisted ones reach the picker.
            val models = CloudProviders.mergeDiscovered(provider.models, discovered)
            if (models.none { it.voices.isNotEmpty() }) {
                return@withContext Result.failure(
                    IOException("${provider.displayName} listed no usable TTS voices"),
                )
            }
            voicesCacheFile(provider.id).writeText(body)
            cached = null
            sync()
            Result.success(models)
        }

    /**
     * Rebuild the Cloud API engine's Room rows: every (model × voice) of
     * every provider whose key is configured. Providers without a key
     * contribute nothing — their voices only appear once the user
     * configures them, which is what gates them out of the pickers and
     * alias editor. Stale rows (removed voices, unkeyed providers, the
     * CATALOG_VERSION-25 static seed) are dropped by the replace.
     */
    suspend fun sync() {
        val keys = settings.cloudApiKeys.first()
        val rows = providers()
            .filter { !keys[it.id].isNullOrBlank() }
            .flatMap { provider ->
                provider.models.flatMap { model ->
                    model.voices.map { CloudApiVoiceCatalog.voiceMeta(provider, model, it) }
                }
            }
            .distinctBy { it.id }
        voiceDao.replaceEngine(CloudApiVoiceCatalog.ENGINE, rows)
    }

    /**
     * The bundled asset, or the cached remote copy when it is at least as
     * new by schema [CloudProvidersDocument.version].
     *
     * The version comparison is load-bearing, not defensive. The cache used
     * to win unconditionally, which meant a device that had ever refreshed
     * pinned that copy forever: shipping a corrected descriptor in an app
     * update did nothing, and a new build's capability fields would be read
     * from an old schema that never had them. Ties go to the cache so a
     * same-version remote can still deliver new models between releases.
     *
     * A winning remote copy still can't move a built-in provider's
     * `baseUrl` off its site — see [CloudProviders.pinBuiltInSites] — nor a
     * keyed one off the site its key was saved for — [pinKeyed].
     */
    private fun loadBaseProviders(): List<CloudProvider> {
        val (bundled, providers) = loadUnkeyedProviders()
        return pinKeyed(bundled, providers)
    }

    /** (bundled providers, the list in force before [pinKeyed]). */
    private fun loadUnkeyedProviders(): Pair<List<CloudProvider>, List<CloudProvider>> {
        val bundled = context.assets.open(BUNDLED_ASSET).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        // A malformed bundled asset is a build error — let it throw.
        val bundledDoc = CloudProviders.parseDocument(bundled)

        val cachedDoc = providersCacheFile.takeIf { it.exists() }?.let { file ->
            runCatching { CloudProviders.parseDocument(file.readText()) }
                .onFailure { Log.w(TAG, "cached provider list unreadable; using bundled", it) }
                .getOrNull()
        } ?: return bundledDoc.providers to bundledDoc.providers

        return bundledDoc.providers to if (cachedDoc.version >= bundledDoc.version) {
            CloudProviders.pinBuiltInSites(bundledDoc.providers, cachedDoc.providers).onEach {
                if (it.movedOffSite) {
                    val moved = cachedDoc.providers.first { remote -> remote.id == it.id }
                    Log.w(
                        TAG,
                        "remote provider list moves ${it.id} off-site to ${moved.baseUrl}; " +
                            "refused, keeping bundled ${it.baseUrl} (app update needed)",
                    )
                }
            }
        } else {
            Log.i(
                TAG,
                "cached provider list is schema v${cachedDoc.version}, " +
                    "bundled is v${bundledDoc.version}; using bundled",
            )
            bundledDoc.providers
        }
    }

    /**
     * Apply [CloudProviders.pinKeyedSites] with the saved keys' URLs.
     *
     * Migration, trust on first use: a key saved before its URL was recorded
     * (1.1.0 and earlier) gets the URL its provider has in this first load,
     * which runs before this process can fetch a new provider list
     * (CloudApiViewModel reads [providers] before [refreshProviders]).
     *
     * runBlocking: [providers] is synchronous (the engine's provider lookup)
     * and this runs once per cache fill, the same trade as
     * CloudApiEngine.isInstalled.
     */
    private fun pinKeyed(bundled: List<CloudProvider>, providers: List<CloudProvider>): List<CloudProvider> {
        val (keys, saved) = runBlocking {
            settings.cloudApiKeys.first() to settings.cloudApiKeyBaseUrls.first()
        }
        // A regional provider has no usable baseUrl until a region picks one,
        // so there is nothing meaningful to record "on first use" — a saved
        // key for it predates the region field and will ask for one again.
        val unrecorded = providers
            .filter {
                !keys[it.id].isNullOrBlank() && it.id !in saved &&
                    !it.needsRegion && it.baseUrl.isNotBlank()
            }
            .associate { it.id to it.baseUrl }
        if (unrecorded.isNotEmpty()) {
            Log.i(TAG, "recording key URLs for ${unrecorded.keys} (first use)")
            runBlocking { settings.recordCloudApiKeyBaseUrls(unrecorded) }
        }
        val keyUrls = saved + unrecorded
        val listed = providers.associateBy { it.id }
        return CloudProviders.pinKeyedSites(bundled, providers, keyUrls).map { provider ->
            val listedUrl = listed.getValue(provider.id).baseUrl
            if (provider.movedOffSite && provider.baseUrl != listedUrl) {
                Log.w(
                    TAG,
                    "provider list moves keyed ${provider.id} off-site to $listedUrl; " +
                        "refused, keeping ${provider.baseUrl} where its key was saved",
                )
            }
            // The URL recorded with the key is exactly where the key was
            // agreed to go, and for a regional provider it also carries the
            // region — so it is the active endpoint, not a site to compare.
            if (provider.needsRegion) {
                keyUrls[provider.id]?.let { provider.copy(resolvedBaseUrl = it) } ?: provider
            } else {
                provider
            }
        }
    }

    companion object {
        private const val TAG = "CloudProviderStore"
        private const val BUNDLED_ASSET = "cloud-providers.json"

        /**
         * Same document as the bundled asset, updatable without a release.
         *
         * Process rule (Max): every change to `cloud-providers.json` in the
         * engines repo needs Max's manual review — agents never merge it.
         * What ships here reaches every installed app, and a provider's
         * `baseUrl` is where users' saved API keys are sent.
         */
        const val REMOTE_PROVIDERS_URL =
            "https://raw.githubusercontent.com/maxwhipw/marmalade-tts-android-engines/main/cloud-providers.json"
    }
}

/**
 * The narrow slice of [CloudProviderStore] the synthesis engine needs
 * (provider lookup for base URLs). An interface so the engine's JVM unit
 * tests can fake it without standing up Context + Room + DataStore.
 */
fun interface CloudProviderDirectory {
    fun providerById(id: String): CloudProvider?
}

/**
 * Injectable seam for the store's GET requests (provider list refresh +
 * voice discovery) so tests serve canned JSON. Mirrors
 * [app.marmalade.tts.engine.api.CloudSpeechHttp].
 *
 * Headers are supplied by the caller because the two API styles
 * authenticate differently: `Authorization: Bearer` for an OpenAI-shaped
 * provider, `Ocp-Apim-Subscription-Key` for Azure.
 */
fun interface CloudJsonHttp {
    /** GET [url] with [headers]; return the body. */
    @Throws(IOException::class)
    fun get(url: String, headers: Map<String, String>): String
}

/** Production [CloudJsonHttp] on HttpURLConnection (no extra deps). */
class UrlCloudJsonHttp @Inject constructor() : CloudJsonHttp {
    override fun get(url: String, headers: Map<String, String>): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            for ((name, value) in headers) setRequestProperty(name, value)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("GET $url: HTTP $code")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
