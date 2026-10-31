package app.marmalade.tts.data.cloud

import app.marmalade.tts.data.LatencyBucket
import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   cloud-providers.json (bundled asset; a cached remote copy wins only if
//   its `version` is >= the bundled one, and even then it may only move a
//   built-in provider's baseUrl within the same site — pinBuiltInSites() —
//   or a keyed provider's within the site its key was saved for —
//   pinKeyedSites())
//     │
//     ▼
//   CloudProviders.parseDocument(json) ──► CloudProvidersDocument
//     │                                      │
//     │                                      ├── discoverVoices = true →
//     │                                      │     CloudProviderStore queries
//     │                                      │     /models?type=tts and MERGES
//     │                                      │     it into the static models
//     │                                      │     via mergeDiscovered()
//     │                                      │
//     │                                      └── discoverVoices = false →
//     │                                            static `models` used as-is
//     ▼
//   CloudProviderStore.sync() ──► VoiceMeta rows for the cloud engine
//
// The static `models` list is an ALLOWLIST, not a fallback. Discovery only
// refreshes the voice arrays of models already listed; a model the provider
// starts serving that isn't in the descriptor is ignored.
//
// This is deliberate and was learned the hard way. The old shape was a
// substring blocklist (`modelExclude`) over a discovery result that
// *replaced* the static list wholesale. It failed OPEN: Venice grew from 3
// to 11 TTS models, and each new one landed straight in users' voice
// pickers untested. Five of them return MP3 regardless of
// `response_format`, and two more return 48 kHz — all of which the engine
// rejects at synthesis time, i.e. after the user has already picked the
// voice and pressed Speak. Failing closed means a model Venice adds is
// invisible until someone verifies it and adds a descriptor entry, which
// is the correct direction to be wrong in.
// -----------------------------------------------------------------------------

/**
 * One hosted TTS provider, described as *data* — adding or updating a
 * provider is a JSON edit (remotely fetchable), never an app release.
 * [api] picks the wire protocol; what varies per provider is otherwise
 * only this metadata.
 *
 * @property id            Stable key — appears inside cloud voice ids and
 *                         per-provider DataStore key names. Never rename.
 * @property displayName   User-facing provider name.
 * @property baseUrl       API root, no trailing slash (e.g.
 *                         `https://api.venice.ai/api/v1`). For a regional
 *                         provider ([needsRegion]) this is a template with
 *                         [AzureSpeech.REGION_TOKEN].
 * @property keyHint       Where the user gets an API key, shown in the
 *                         configure dialog.
 * @property api           The wire protocol. Absent in JSON means
 *                         [CloudApiStyle.OPENAI].
 * @property needsRegion   True when the provider's endpoint is regional and
 *                         the user must supply a region with the key. The
 *                         active endpoint is then [resolvedBaseUrl].
 * @property discoverVoices True when the provider serves a live voice list.
 *                         For [CloudApiStyle.OPENAI] that is
 *                         `GET {baseUrl}/models?type=tts` whose entries carry
 *                         `model_spec.voices` (Venice does); for
 *                         [CloudApiStyle.AZURE] it is
 *                         `GET {endpoint}/cognitiveservices/voices/list`. In
 *                         both, discovery refreshes the voice arrays of
 *                         [models]; it cannot introduce a model that isn't
 *                         already listed. An Azure model may ship an empty
 *                         static list because discovery is its only source.
 * @property models        The allowlist: every model this app will speak
 *                         through, with its verified capabilities.
 * @property movedOffSite  Not part of the JSON. True when the downloaded
 *                         provider list moved this provider to a different
 *                         site and the move was refused — a built-in one
 *                         ([CloudProviders.pinBuiltInSites]) or one the user
 *                         saved a key for ([CloudProviders.pinKeyedSites]);
 *                         [baseUrl] is then still the pinned one, and the UI
 *                         tells a user with a saved key to update the app.
 * @property resolvedBaseUrl Not part of the JSON. For a [needsRegion]
 *                         provider, the endpoint its saved region resolves
 *                         to ([CloudProviderStore] fills it from the URL
 *                         recorded with the key). Null until a key with a
 *                         region is saved.
 */
data class CloudProvider(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val keyHint: String,
    val discoverVoices: Boolean,
    val models: List<CloudModel>,
    val api: CloudApiStyle = CloudApiStyle.OPENAI,
    val needsRegion: Boolean = false,
    val movedOffSite: Boolean = false,
    val resolvedBaseUrl: String? = null,
) {
    /**
     * The URL requests go to, or null when this provider needs a region and
     * the user hasn't supplied one yet. A non-regional provider always has
     * one ([baseUrl]).
     */
    fun endpoint(): String? = if (needsRegion) resolvedBaseUrl else baseUrl

    /** The endpoint this provider would have in [region]. */
    fun endpointForRegion(region: String): String =
        if (needsRegion) AzureSpeech.endpointFor(baseUrl, region) else baseUrl
}

/**
 * One TTS model a provider serves, with its voice list and the capabilities
 * that were *measured* against it.
 *
 * @property sampleRate The rate this model actually returns, in Hz. Not a
 *   preference — the wire format is whatever the model emits, and the
 *   system-TTS path has to commit to a rate in `callback.start()` before a
 *   single byte arrives (see MarmaladeTtsService), so it must be declared
 *   ahead of time. [CloudApiEngine] re-checks the real WAV header against
 *   this value and fails loudly on a mismatch, so a wrong number here
 *   surfaces as a clear error rather than audio at the wrong pitch.
 *
 *   Measured for Venice 2026-07-24: Kokoro and xAI 24 kHz, Gradium and
 *   Inworld 48 kHz. Do not guess this field — synthesize one clip and read
 *   the header.
 *
 * @property latency A *seed* for the picker's speed badge: `instant`,
 *   `quick` or `slow`, absent when nobody has checked. Unlike [sampleRate]
 *   this one is safe to be roughly wrong — it is a hint, not a contract, and
 *   the device replaces it with its own median once the model has been used
 *   a few times (see [app.marmalade.tts.data.VoiceLatencyTracker]). It lives
 *   here rather than in Kotlin precisely so a provider that gets faster can
 *   be corrected by publishing a descriptor, without an app release.
 *
 * @property voiceInfo Per-voice metadata keyed by voice id, filled by live
 *   discovery for providers whose voice list carries it (Azure). Empty for
 *   providers whose voices are bare ids (OpenAI, Venice), whose rows keep
 *   the id-derived heuristics in [app.marmalade.tts.data.CloudApiVoiceCatalog].
 */
data class CloudModel(
    val id: String,
    val displayName: String,
    val voices: List<String>,
    val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    val latency: LatencyBucket? = null,
    val voiceInfo: Map<String, CloudVoiceInfo> = emptyMap(),
) {
    companion object {
        /** Kokoro's rate; the historical assumption for every cloud model. */
        const val DEFAULT_SAMPLE_RATE = 24_000
    }
}

/**
 * What a provider discloses about one voice beyond its id: how to label it
 * and which language it speaks. Azure's voice list has all three; the
 * OpenAI-shaped lists have none, so their rows derive what they can from the
 * id.
 *
 * @property displayName The voice's own name ("Jenny"), not its ShortName.
 * @property languageCode BCP-47 tag ("en-US"); the engine's placeholder
 *   "en-US" for a multilingual voice would make language routing believe it
 *   is English only, so a provider that knows the language says so here.
 * @property gender `"female"` / `"male"`, or null when unspecified — the
 *   picker's stored convention (`VoiceMeta.gender`).
 */
data class CloudVoiceInfo(
    val displayName: String,
    val languageCode: String,
    val gender: String? = null,
)

/**
 * A parsed `cloud-providers.json`, including its schema [version].
 *
 * The version exists so a newer bundled asset can beat a stale cached
 * remote copy — without it, a device that ever fetched the remote
 * descriptor would pin that copy forever and never see a corrected
 * descriptor shipped in an app update.
 */
data class CloudProvidersDocument(
    val version: Int,
    val providers: List<CloudProvider>,
)

object CloudProviders {

    /**
     * Parse a `cloud-providers.json` document. Throws [JSONException] on a
     * malformed document — callers decide whether that means "fall back to
     * the bundled asset" (remote copy) or is fatal (bundled asset broken =
     * programmer error).
     */
    fun parseDocument(json: String): CloudProvidersDocument {
        val root = JSONObject(json)
        val providers = root.getJSONArray("providers")
        return CloudProvidersDocument(
            // A document without a version predates the field; treat it as
            // the oldest possible so any versioned copy supersedes it.
            version = root.optInt("version", 0),
            providers = (0 until providers.length()).map { i ->
                val p = providers.getJSONObject(i)
                val baseUrl = requireHttps(p.getString("baseUrl").trimEnd('/'))
                val api = CloudApiStyle.parse(p.optString("api"))
                    ?: throw JSONException(
                        "Unknown cloud provider api '${p.optString("api")}' for ${p.getString("id")}",
                    )
                val needsRegion = p.optBoolean(
                    "needsRegion",
                    api == CloudApiStyle.AZURE && AzureSpeech.REGION_TOKEN in baseUrl,
                ) || AzureSpeech.REGION_TOKEN in baseUrl
                if (needsRegion && AzureSpeech.REGION_TOKEN !in baseUrl) {
                    throw JSONException(
                        "Regional provider ${p.getString("id")} has no " +
                            "${AzureSpeech.REGION_TOKEN} in its baseUrl",
                    )
                }
                CloudProvider(
                    id = p.getString("id"),
                    displayName = p.getString("displayName"),
                    baseUrl = baseUrl,
                    keyHint = p.optString("keyHint"),
                    discoverVoices = p.optBoolean("discoverVoices", false),
                    models = parseModels(p.optJSONArray("models")),
                    api = api,
                    needsRegion = needsRegion,
                )
            },
        )
    }

    /**
     * Reject any provider endpoint that isn't HTTPS.
     *
     * This document is fetched from the network ([CloudProviderStore.
     * REMOTE_PROVIDERS_URL]) so a new provider can ship without an app
     * update — which means `baseUrl` is remote input, and the user's text
     * plus their API key are what get POSTed to it. The platform already
     * blocks cleartext (targetSdk 36 defaults `usesCleartextTraffic` to
     * false and no manifest overrides it), but that is a default someone
     * could switch off years from now without connecting it to this.
     * Refusing at the parse boundary makes "encrypted in transit" a
     * property of the code rather than of a build setting.
     *
     * Throws [JSONException] rather than a bespoke type so it lands in the
     * same handler as any other malformed document: the remote copy is
     * discarded and the bundled asset stays in force.
     */
    private fun requireHttps(url: String): String {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw JSONException("Provider baseUrl must be https, got: $url")
        }
        return url
    }

    /**
     * Apply a downloaded provider list on top of the bundled one without
     * letting it move a built-in provider's endpoint to another site.
     *
     * Every provider's `baseUrl` receives the user's saved API key as
     * `Authorization: Bearer`, and the remote list is fetched from GitHub.
     * A tampered or mistaken remote file could otherwise send every user's
     * saved key to a host nobody reviewed. So, for a provider id the
     * bundled list also has, the remote `baseUrl` is accepted only when it
     * is [sameSite] as the bundled one (https, same registrable domain;
     * port and path may change). Otherwise the bundled `baseUrl` stays in
     * force — every other remote field (models, voices, hints) still
     * applies — and the provider is flagged [CloudProvider.movedOffSite]
     * so the UI can ask the user to update the app.
     *
     * Providers only in [remote] are taken as-is here: the user sees a new
     * provider before entering a key for it, and [parseDocument] has already
     * required https. Once a key is saved for one, [pinKeyedSites] holds it
     * to the site of the URL the key was saved for.
     *
     * The remote list stays authoritative for which providers exist and in
     * what order.
     */
    fun pinBuiltInSites(
        bundled: List<CloudProvider>,
        remote: List<CloudProvider>,
    ): List<CloudProvider> {
        val bundledById = bundled.associateBy { it.id }
        return remote.map { provider ->
            val builtIn = bundledById[provider.id] ?: return@map provider
            if (sameSite(builtIn.baseUrl, provider.baseUrl)) {
                provider
            } else {
                provider.copy(baseUrl = builtIn.baseUrl, movedOffSite = true)
            }
        }
    }

    /**
     * Hold every provider the user saved a key for to the site of the
     * `baseUrl` it was saved for ([keyBaseUrls], provider id → that URL; see
     * SettingsRepository.cloudApiKeyBaseUrls). The same rule as
     * [pinBuiltInSites]: a [sameSite] move is taken, an off-site one keeps
     * the saved URL and flags [CloudProvider.movedOffSite].
     *
     * This closes the gap [pinBuiltInSites] leaves: a provider that exists
     * only in the downloaded list would otherwise be trusted as-is on every
     * load, so a later tampered list could send the saved key anywhere.
     * Providers in [bundled] keep their bundled pin and are left alone here;
     * so is any provider without a saved key.
     */
    fun pinKeyedSites(
        bundled: List<CloudProvider>,
        providers: List<CloudProvider>,
        keyBaseUrls: Map<String, String>,
    ): List<CloudProvider> {
        val bundledIds = bundled.mapTo(HashSet()) { it.id }
        return providers.map { provider ->
            if (provider.id in bundledIds) return@map provider
            val saved = keyBaseUrls[provider.id] ?: return@map provider
            if (sameSite(saved, provider.baseUrl)) {
                provider
            } else {
                provider.copy(baseUrl = saved, movedOffSite = true)
            }
        }
    }

    /**
     * True when [candidate] is an https URL on the same site as [trusted].
     * An exact host match always passes (this covers IP literals and
     * single-label hosts); otherwise both hosts must share
     * [registrableDomain]. Unparseable URLs fail.
     */
    fun sameSite(trusted: String, candidate: String): Boolean {
        // A regional template isn't a parseable URI until its placeholder is
        // a valid label. Substituting a fixed one keeps the site comparison
        // meaningful — the user's region varies, the site does not.
        val trustedUrl = trusted.replace(AzureSpeech.REGION_TOKEN, "region")
        val candidateUrl = candidate.replace(AzureSpeech.REGION_TOKEN, "region")
        val candidateUri = runCatching { URI(candidateUrl) }.getOrNull() ?: return false
        if (!candidateUri.scheme.equals("https", ignoreCase = true)) return false
        val candidateHost = hostOf(candidateUrl) ?: return false
        val trustedHost = hostOf(trustedUrl) ?: return false
        if (candidateHost == trustedHost) return true
        return registrableDomain(candidateHost) == registrableDomain(trustedHost)
    }

    private fun hostOf(url: String): String? =
        runCatching { URI(url).host }.getOrNull()
            ?.lowercase()
            ?.trimEnd('.')
            ?.takeIf { it.isNotEmpty() }

    /**
     * An approximation of the registrable domain ("eTLD+1") of [host].
     *
     * The real answer needs the Public Suffix List, and nothing this app
     * depends on ships one (the Android SDK has no public API for it and
     * there is no OkHttp here). So the rule is deliberately conservative:
     *
     *  - Normally the last two labels: `api.venice.ai` → `venice.ai`.
     *  - When the top-level label is two letters (a country code) and the
     *    label before it looks like that country's second-level registry —
     *    three letters or fewer (`co`, `com`, `org`, `ne`, `ac`, `gob`…) or
     *    one of [LONG_CCTLD_SECOND_LEVELS] — the last three:
     *    `api.example.co.uk` → `example.co.uk`, not `co.uk`.
     *
     * Known limits:
     *  - The short-label test over-matches. `api.x.ai` reads `x.ai` as a
     *    registry, so its site becomes `api.x.ai` and a move to `other.x.ai`
     *    is refused. That is the safe direction to be wrong in: the
     *    provider keeps working on its bundled URL and the user is told to
     *    update.
     *  - Multi-label public suffixes under generic TLDs are invisible to it
     *    (`github.io`, `herokuapp.com`, `pages.dev`, `cloudfront.net`…). A
     *    built-in provider hosted on one of those would share a "site" with
     *    every other tenant there. None of the bundled providers is; check
     *    for this before bundling one that is.
     *  - IP-literal hosts only ever match exactly (see [sameSite]).
     */
    private fun registrableDomain(host: String): String {
        val labels = host.split('.')
        val secondLevel = labels.getOrNull(labels.size - 2).orEmpty()
        val underCcTldRegistry = labels.size >= 3 &&
            labels.last().length == 2 &&
            (secondLevel.length <= 3 || secondLevel in LONG_CCTLD_SECOND_LEVELS)
        return labels.takeLast(if (underCcTldRegistry) 3 else 2).joinToString(".")
    }

    /** ccTLD second-level registries longer than three letters. */
    private val LONG_CCTLD_SECOND_LEVELS = setOf("gouv", "govt", "police", "school", "info", "firm", "priv")

    /** Providers only — for callers that don't care about the version. */
    fun parse(json: String): List<CloudProvider> = parseDocument(json).providers

    /**
     * Parse a provider's live `GET /models?type=tts` response (the shape
     * Venice serves, mirrored from the CLI's `list_voices`): a `data`
     * array of `{id, model_spec: {name, voices: [...]}}`. Entries without
     * voices are dropped.
     *
     * This returns everything the provider advertises. Filtering to the
     * allowlist is [mergeDiscovered]'s job — keeping the two apart means
     * the raw response can be cached verbatim and re-filtered when the
     * descriptor changes, without re-hitting the network.
     */
    fun parseDiscoveredModels(json: String): List<CloudModel> {
        val root = JSONObject(json)
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            if (id.isBlank()) return@mapNotNull null
            val spec = m.optJSONObject("model_spec")
            val voices = spec?.optJSONArray("voices").toStringList()
            if (voices.isEmpty()) return@mapNotNull null
            CloudModel(
                id = id,
                displayName = spec?.optString("name").orEmpty().ifBlank { id },
                voices = voices,
            )
        }
    }

    /**
     * Parse a provider's live voice list in whatever shape its [CloudApiStyle]
     * serves. [CloudProviderStore] caches the raw body and re-runs this on
     * every load, so it must be a pure parse.
     */
    fun parseDiscovery(provider: CloudProvider, json: String): List<CloudModel> =
        when (provider.api) {
            CloudApiStyle.OPENAI -> parseDiscoveredModels(json)
            CloudApiStyle.AZURE -> parseAzureVoices(json, provider.models.firstOrNull())
        }

    /**
     * Parse Azure's `GET /cognitiveservices/voices/list` response: a top-level
     * array of voices, each with `ShortName`, `DisplayName`, `Locale`,
     * `Gender` and `Status`. The service has no model dimension, so every
     * voice belongs to [model] (the descriptor's single allowlisted model)
     * and its rate stays the descriptor's — the output format header, not the
     * voice, decides the sample rate.
     *
     * Deprecated voices are skipped; the list is otherwise taken as served.
     * That is safe for Azure in a way it is not for an OpenAI-shaped provider:
     * every Azure voice speaks the same SSML protocol, so a newly added one
     * can't break the wire contract.
     */
    fun parseAzureVoices(json: String, model: CloudModel?): List<CloudModel> {
        model ?: return emptyList()
        val root = JSONArray(json)
        val voices = mutableListOf<String>()
        val info = mutableMapOf<String, CloudVoiceInfo>()
        for (i in 0 until root.length()) {
            val v = root.optJSONObject(i) ?: continue
            val id = v.optString("ShortName").trim()
            if (id.isEmpty() || v.optString("Status").equals("Deprecated", ignoreCase = true)) continue
            if (id in info) continue
            voices += id
            info[id] = CloudVoiceInfo(
                displayName = v.optString("DisplayName").ifBlank { id },
                languageCode = v.optString("Locale").takeIf { it.isNotBlank() }
                    ?: AzureSpeech.localeOf(id) ?: "en-US",
                gender = v.optString("Gender").lowercase()
                    .takeIf { it == "female" || it == "male" },
            )
        }
        if (voices.isEmpty()) return emptyList()
        return listOf(model.copy(voices = voices, voiceInfo = info))
    }

    /**
     * Join a live-discovery result onto the descriptor's allowlist.
     *
     * Voices come from [discovered] (the provider is authoritative about
     * which voices exist); everything else — crucially [CloudModel.sampleRate]
     * — comes from [allowed], because the provider's list response carries
     * no capability data. A discovered model with no descriptor entry is
     * dropped; an allowed model absent from discovery keeps its static voice
     * list so an unreachable network degrades to the bundled catalog rather
     * than an empty picker.
     */
    fun mergeDiscovered(
        allowed: List<CloudModel>,
        discovered: List<CloudModel>,
    ): List<CloudModel> {
        val byId = discovered.associateBy { it.id }
        return allowed.map { model ->
            val live = byId[model.id] ?: return@map model
            model.copy(
                displayName = live.displayName.ifBlank { model.displayName },
                voices = live.voices,
                voiceInfo = live.voiceInfo,
            )
        }
    }

    private fun parseModels(arr: JSONArray?): List<CloudModel> {
        arr ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            CloudModel(
                id = m.getString("id"),
                displayName = m.optString("displayName").ifBlank { m.getString("id") },
                voices = m.optJSONArray("voices").toStringList(),
                sampleRate = m.optInt("sampleRate", CloudModel.DEFAULT_SAMPLE_RATE),
                latency = LatencyBucket.parse(m.optString("latency").ifBlank { null }),
            )
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        this ?: return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
