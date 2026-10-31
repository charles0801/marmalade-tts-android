package app.marmalade.tts.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.marmalade.tts.BuildConfig
import app.marmalade.tts.install.EngineCatalog
import app.marmalade.tts.perf.KittenRtfMeasurement
import app.marmalade.tts.perf.RollingRtf
import app.marmalade.tts.preprocessing.EngineProfiles
import app.marmalade.tts.service.KeepaliveMode
import app.marmalade.tts.ui.theme.ThemePreset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// -----------------------------------------------------------------------------
// Data flow
// -----------------------------------------------------------------------------
//   UI / ViewModel
//     │
//     ├── reads:  SettingsRepository.<flag>  ──► Flow<T>
//     │             │                              │
//     │             ▼                              │
//     │           DataStore<Preferences>.data ─ map { it[KEY] ?: FALLBACK }
//     │
//     └── writes: SettingsRepository.set<Flag>(value)
//                   │
//                   ▼
//                 DataStore.edit { it[KEY] = value }
//
//   Per-engine preprocessing rule sets:
//     enabledRules(engine)        ──► Flow<Set<String>>
//        falls back to EngineProfiles.defaultsFor(engine) when unset.
//     setEnabledRules(engine, …)  ──► CSV-joined string stored under
//                                       "preprocessing_rules_<engine>"
// -----------------------------------------------------------------------------

/**
 * Persists user settings backed by the `marmalade_settings` DataStore.
 *
 * Keeping these in DataStore (instead of Room) matches the SPEC's split:
 * Room for content (voices, aliases, history), DataStore for preferences.
 *
 * The fallback voice is [KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID]
 * (`kokoro-direct-v1_0:af_bella`) — the recommended, release-installable
 * default. We don't seed DataStore on first launch; the Flow's map applies
 * the fallback transparently until the user picks something explicit.
 */
@Singleton
open class SettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    /** This build's versionCode; stamps the measured RTFs ([engineRtf]). */
    private val appVersionCode: Int,
) {
    @Inject constructor(dataStore: DataStore<Preferences>) : this(dataStore, BuildConfig.VERSION_CODE)

    /**
     * Emits the persisted default voice ID, falling back to
     * [KokoroDirectVoiceCatalog.DEFAULT_VOICE_ID] when nothing is stored.
     *
     * Cold Flow — every collector triggers a fresh read; downstream
     * `stateIn(...)` in ViewModels caches the latest value.
     *
     * `open` so JVM unit tests can subclass with an in-memory backing
     * store instead of standing up a real DataStore. (Hilt is fine with
     * the open modifier — final isn't required for `@Inject` providers.)
     */
    open val defaultVoiceId: Flow<String> = dataStore.data.map { prefs ->
        // Default to Kitten (Bella): it's baked into the APK, so it's the one
        // engine guaranteed present on a fresh install — even fully offline,
        // before any download. A Kokoro default would fail the first synth on
        // a fresh offline install because Kokoro isn't installed yet. (The old
        // comment here predates Kitten Direct + baking: the current Kitten is a
        // real direct-ORT engine, not the retired sherpa Kitten Mini.)
        prefs[KEY_DEFAULT_VOICE_ID] ?: KittenDirectVoiceCatalog.DEFAULT_VOICE_ID
    }

    /**
     * Persists [id] as the new default voice. Suspending because DataStore's
     * edit is async; callers should run from a coroutine scope.
     */
    open suspend fun setDefaultVoiceId(id: String) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_VOICE_ID] = id
        }
    }

    /**
     * True once the user has completed (or dismissed) the onboarding flow.
     *
     * Defaults to `false` for fresh installs — `AppRoot` reads this to
     * decide whether to route to the onboarding wizard or straight to the
     * Speak screen. Flipped to `true` exactly once, on the last step of
     * onboarding (even if the user chose to install zero engines — they
     * can install later from the Engines tab).
     */
    open val onboarded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDED] ?: false
    }

    /**
     * Marks onboarding as complete. Should be called from the final
     * onboarding step's "Continue" button handler. Idempotent — calling
     * it on an already-onboarded user is a successful no-op.
     */
    open suspend fun setOnboarded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_ONBOARDED] = value
        }
    }

    /**
     * Whether the baked default engine (Kitten) has been seeded from the APK
     * assets. Seeding runs exactly once (first run) — see
     * `MarmaladeTtsApplication`. Gating on this rather than "is Kitten
     * installed" means a user who deliberately uninstalls Kitten to reclaim
     * space isn't re-seeded on the next launch.
     */
    open val bakedDefaultSeeded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_BAKED_DEFAULT_SEEDED] ?: false
    }

    /** Records that the baked default engine seed has run. Idempotent. */
    open suspend fun setBakedDefaultSeeded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_BAKED_DEFAULT_SEEDED] = value
        }
    }

    /**
     * [app.marmalade.tts.data.db.VoiceAlias.id] of the alias designated
     * **primary** — the fallback voice/effect/speed bundle used when no
     * per-app rule matches, or when an external caller asks marmalade to
     * speak without naming a voice.
     *
     * Emits `null` when no primary is set (fresh install, or the primary
     * was deleted). Callers must treat a stale pointer as null and
     * re-derive; the id is deliberately not a foreign key.
     *
     * Held an alias *name* before db v10, which meant a rename silently
     * detached the primary unless every write site remembered to retarget
     * it. Ids do not need remembering.
     *
     * Stored as a user preference rather than a column on `VoiceAlias` so
     * the alias table has no "is primary" flag to keep consistent.
     */
    open val primaryAliasId: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_PRIMARY_ALIAS_ID]
    }

    /**
     * Set (or clear) the primary alias pointer.
     *
     * Passing `null` removes the key from the DataStore rather than
     * storing an empty string — keeps the "no primary set" state
     * indistinguishable from a fresh install on read.
     */
    open suspend fun setPrimaryAliasId(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) {
                prefs.remove(KEY_PRIMARY_ALIAS_ID)
            } else {
                prefs[KEY_PRIMARY_ALIAS_ID] = value
            }
        }
    }

    /**
     * Voice ids this engine has presented to system TTS clients as the
     * **English default** — every answer
     * [app.marmalade.tts.service.MarmaladeTtsService.onGetDefaultVoiceNameFor]
     * has given, plus every voice the primary alias has spoken with.
     *
     * Clients auto-fill `SynthesisRequest.voiceName` from that answer and
     * may cache it for the life of their own process. Once the user changes
     * the primary alias's voice, an old value keeps arriving looking exactly
     * like a deliberate caller pick — the "system TTS keeps speaking the
     * first voice I chose" bug. The synthesis path treats a member of this
     * set as an echo of our default rather than a pick, so the current
     * primary alias wins.
     *
     * Non-English defaults are deliberately **not** recorded: they name a
     * voice of the requested language, and those requests must keep beating
     * the (English-shaped) primary alias.
     */
    open val advertisedDefaultVoiceIds: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[KEY_ADVERTISED_DEFAULT_VOICE_IDS] ?: emptySet()
    }

    /**
     * Record [voiceId] as a default this engine has advertised. A no-op
     * when it is already known, so the negotiation callback (which can fire
     * per `setLanguage`) does not write to disk every time.
     */
    open suspend fun recordAdvertisedDefaultVoice(voiceId: String) {
        if (voiceId.isBlank()) return
        if (voiceId in advertisedDefaultVoiceIds.first()) return
        dataStore.edit { prefs ->
            prefs[KEY_ADVERTISED_DEFAULT_VOICE_IDS] =
                (prefs[KEY_ADVERTISED_DEFAULT_VOICE_IDS] ?: emptySet()) + voiceId
        }
    }

    /**
     * Emits the persisted theme preset name (one of [ThemePreset.name]),
     * falling back to [ThemePreset.MARMALADE]'s name when nothing is stored.
     *
     * Stored as a string (not an int ordinal) so reordering the enum in a
     * future release doesn't silently re-skin existing installs.
     *
     * Default changed from SYSTEM → MARMALADE in v0.1.10: the orange palette
     * is the brand identity, and System (Material You) felt arbitrary as
     * the default for new installs.
     */
    open val themePreset: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_PRESET] ?: ThemePreset.MARMALADE.name
    }

    /** Persists [value] (a [ThemePreset.name]) as the new theme preset. */
    open suspend fun setThemePreset(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_PRESET] = value
        }
    }

    /**
     * Emits the user's dark-mode override: `"system"` / `"light"` / `"dark"`.
     * Defaults to `"system"` (follow the OS).
     *
     * Decoupled from [themePreset] — preset is the color *family* (Marmalade,
     * Midnight, etc.) and themeMode is the *brightness* (light/dark/auto).
     * Resolved at the theme-application site via
     * [app.marmalade.tts.ui.theme.resolveThemeIsDark].
     */
    open val themeMode: Flow<String> = dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    /** Persists the dark-mode override. Caller is responsible for normalising the input. */
    open suspend fun setThemeMode(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = value
        }
    }

    /**
     * True when the synth engine should stay loaded between utterances
     * (faster speak-onset, costs ~40 MB resident memory). Default is
     * `true` — matches the v0.1.x behavior pre-toggle.
     *
     * TODO(v0.2): the `false` branch (release engine between utterances)
     * is not yet wired into [KittenEngine] / [KokoroEngine]. The Settings
     * UI toggle was *hidden in v0.1.16* because the engines ignored this
     * flag — surfacing a dead control as a real Switch was misleading.
     * The storage stays in place (cheap, doesn't hurt, keeps the key
     * stable across the rename) so we can re-add the UI without a
     * migration when the engines start honouring it. See STUBS.md.
     */
    open val keepEngineLoaded: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_KEEP_LOADED] ?: true
    }

    /** Persists the keep-engine-loaded toggle. */
    open suspend fun setKeepEngineLoaded(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEP_LOADED] = value
        }
    }

    /**
     * P-K — engine keepalive mode. Drives [MarmaladeKeepaliveService]:
     *
     *   - [KeepaliveMode.Off]:        no service
     *   - [KeepaliveMode.Smart]:      service starts after each synth,
     *                                 stops after 10 minutes of idle
     *   - [KeepaliveMode.Persistent]: service runs forever (opt-in)
     *
     * Defaults to [KeepaliveMode.Smart] — sub-realtime synth on Pocket
     * (per P-V) makes the 1-second cold load the dominant felt-latency
     * for second-and-later synths, so smoothing it over a 10-minute
     * window is a near-pure win at zero long-term cost.
     */
    open val keepaliveMode: Flow<KeepaliveMode> = dataStore.data.map { prefs ->
        prefs[KEY_KEEPALIVE_MODE]?.let { name ->
            KeepaliveMode.entries.firstOrNull { it.name == name }
        } ?: KeepaliveMode.Smart
    }

    /** Persist the keepalive mode. */
    open suspend fun setKeepaliveMode(mode: KeepaliveMode) {
        dataStore.edit { prefs ->
            prefs[KEY_KEEPALIVE_MODE] = mode.name
        }
    }

    /**
     * The catalog version that the on-device DB was last seeded against.
     *
     * `MarmaladeTtsApplication.onCreate` compares this against the current
     * [app.marmalade.tts.MarmaladeTtsApplication.CATALOG_VERSION] constant
     * and re-runs `voiceDao.upsertAll(...)` for every catalog whose
     * version increased. That keeps users on the same DB-row set as the
     * shipped app, without ever destructively wiping the table — Room's
     * REPLACE-on-conflict upsert just refreshes the metadata.
     *
     * Stored as an integer rather than a hash so the upgrade path is
     * monotonic and trivially comparable; bump by 1 in
     * `MarmaladeTtsApplication` every time a catalog's voice rows change
     * (add/remove a voice, change a language code, etc.).
     *
     * Defaults to 0 — fresh installs go through the same code path as an
     * upgrade and pick up the latest catalog on first run.
     */
    open val catalogVersion: Flow<Int> = dataStore.data.map { prefs ->
        prefs[KEY_CATALOG_VERSION] ?: 0
    }

    /** Persist [version] as the latest catalog version seeded into Room. */
    open suspend fun setCatalogVersion(version: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_CATALOG_VERSION] = version
        }
    }

    /**
     * The notes version (a versionCode, see
     * [app.marmalade.tts.ui.whatsnew.WhatsNew.NOTES_VERSION]) of the last
     * "What's new" pop-up the user dismissed, or that a fresh install
     * skipped. `null` on installs from before 1.2, which never stored it;
     * [app.marmalade.tts.ui.whatsnew.WhatsNew.decide] tells those apart from
     * fresh installs by the onboarded flag.
     */
    open val whatsNewSeenVersion: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[KEY_WHATS_NEW_SEEN_VERSION]
    }

    /** Persist [version] as the last "What's new" notes the user has seen. */
    open suspend fun setWhatsNewSeenVersion(version: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_WHATS_NEW_SEEN_VERSION] = version
        }
    }

    /**
     * The set of enabled text-preprocessing rule names for [engineName].
     *
     * Stored as a comma-separated string under
     * `preprocessing_rules_<engineName>` to avoid pulling in
     * kotlinx-serialization just for this — rule names are stable
     * identifiers from `PreprocessingRules.ALL` (no commas, no special
     * chars to escape).
     *
     * Falls back to [EngineProfiles.defaultsFor] when nothing is
     * persisted yet. That gives a fresh install the CLI's per-engine
     * defaults without us having to seed DataStore on first launch
     * (the seed would race with first-read collectors in the UI).
     *
     * The empty-string case is treated as "user disabled everything"
     * (returns the empty set), distinct from "nothing stored yet"
     * (returns the defaults).
     */
    open fun enabledRules(engineName: String): Flow<Set<String>> {
        val key = preprocessingKeyFor(engineName)
        return dataStore.data.map { prefs ->
            val stored = prefs[key]
            when {
                stored == null -> EngineProfiles.defaultsFor(engineName)
                stored.isEmpty() -> emptySet()
                else -> stored.split(",").filter { it.isNotBlank() }.toSet()
            }
        }
    }

    /**
     * Persist [rules] as the enabled set for [engineName]. Stored
     * verbatim as a comma-joined string; the empty set is stored as
     * `""` (not removed) so the "user disabled everything" state
     * round-trips correctly.
     */
    open suspend fun setEnabledRules(engineName: String, rules: Set<String>) {
        val key = preprocessingKeyFor(engineName)
        dataStore.edit { prefs ->
            prefs[key] = rules.joinToString(",")
        }
    }

    private fun preprocessingKeyFor(engineName: String) =
        stringPreferencesKey("preprocessing_rules_$engineName")

    /**
     * Per-device ONNX-Runtime intra-op thread count. `null` means "auto" —
     * the engine calls [app.marmalade.tts.perf.CpuClusterDetector] at init
     * time to pick a value matching the CPU's perf-cluster size. Manual
     * overrides are stored as a positive Int; values ≤ 0 or unset map to
     * auto.
     *
     * Exposed in Settings because the auto heuristic can be wrong on
     * exotic CPU topologies (some Samsungs split a "prime" core off the
     * usual big cluster; some MediaTek chips have three-tier hierarchies).
     * Users with reliable benchmarks can pin their preferred value.
     */
    open val intraOpThreads: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[KEY_INTRA_OP_THREADS]?.takeIf { it > 0 }
    }

    /** Persist a manual thread count, or pass `null` to revert to auto. */
    open suspend fun setIntraOpThreads(count: Int?) {
        dataStore.edit { prefs ->
            if (count == null || count <= 0) {
                prefs.remove(KEY_INTRA_OP_THREADS)
            } else {
                prefs[KEY_INTRA_OP_THREADS] = count
            }
        }
    }

    /**
     * Whether developer-only engines (currently the Pocket clean-reference
     * diagnostic build) are surfaced in the engine lists. They stay in the
     * catalog for A/B comparison but are hidden from normal users by default.
     *
     * Defaults to [BuildConfig.DEBUG]: shown in debug builds (so we keep the
     * A/B affordance), hidden in release builds where they're an explicit
     * opt-in. The user can flip it either way; routing never consults this
     * flag, so an alias already pointing at a hidden engine keeps
     * synthesizing even while the engine is hidden.
     *
     * This is the EFFECTIVE value ([EngineCatalog.effectiveDeveloperMode]):
     * always false in the Play build, where the toggle is hidden, whatever
     * is stored.
     */
    open val showDeveloperEngines: Flow<Boolean> = dataStore.data.map { prefs ->
        EngineCatalog.effectiveDeveloperMode(prefs[KEY_SHOW_DEVELOPER_ENGINES] ?: BuildConfig.DEBUG)
    }

    /**
     * Per-provider API keys for the Cloud API engine, keyed by
     * [app.marmalade.tts.data.cloud.CloudProvider.id]. Empty map = engine
     * unconfigured, which the app treats as "not installed"
     * ([app.marmalade.tts.engine.api.CloudApiEngine.isInstalled]).
     *
     * The single-provider era stored one key under `cloud_api_key`; it is
     * read here as Venice's key until a per-provider write replaces it.
     *
     * Plain DataStore is acceptable here: the file is app-private and
     * `android:allowBackup="false"` keeps it out of device backups.
     * Never log these values.
     */
    open val cloudApiKeys: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val keys = mutableMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            val providerId = key.name.removePrefix(CLOUD_API_KEY_PREFIX)
            if (providerId != key.name && value is String && value.isNotBlank()) {
                keys[providerId] = value
            }
        }
        val legacy = prefs[KEY_CLOUD_API_KEY]
        if (!legacy.isNullOrBlank() && LEGACY_CLOUD_PROVIDER !in keys) {
            keys[LEGACY_CLOUD_PROVIDER] = legacy
        }
        keys
    }

    /** One provider's Cloud API key; blank when unconfigured. */
    open fun cloudApiKeyFor(providerId: String): Flow<String> =
        cloudApiKeys.map { it[providerId] ?: "" }

    /** True when at least one cloud provider has a key. */
    open val anyCloudApiKeySet: Flow<Boolean> = cloudApiKeys.map { it.isNotEmpty() }

    /**
     * True once the user has read and accepted the cloud disclaimer — that
     * choosing a cloud voice sends the text to be spoken to a third party.
     *
     * Gates the Cloud voices configure screen: no key can be entered until
     * this is true, so acceptance always precedes the first byte leaving the
     * device. Deliberately **not** cleared when the last key is removed —
     * this records "was told," not "is using," and re-prompting someone who
     * already read it trains people to click through disclaimers.
     */
    open val cloudDisclaimerAccepted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_DISCLAIMER_ACCEPTED] ?: false
    }

    /** Records acceptance of the cloud disclaimer. One-way by design. */
    open suspend fun acceptCloudDisclaimer() {
        dataStore.edit { prefs ->
            prefs[KEY_CLOUD_DISCLAIMER_ACCEPTED] = true
        }
    }

    /**
     * Persist [providerId]'s Cloud API key, together with the [baseUrl] the
     * provider had when the user saved it; blank removes both.
     *
     * The URL is the pin for providers the app doesn't bundle: a later
     * downloaded provider list may move such a provider only within that
     * URL's site ([cloudApiKeyBaseUrls],
     * [app.marmalade.tts.data.cloud.CloudProviders.pinKeyedSites]).
     */
    open suspend fun setCloudApiKey(providerId: String, value: String, baseUrl: String) {
        dataStore.edit { prefs ->
            val trimmed = value.trim()
            val key = stringPreferencesKey("$CLOUD_API_KEY_PREFIX$providerId")
            val urlKey = stringPreferencesKey("$CLOUD_API_KEY_BASE_URL_PREFIX$providerId")
            if (trimmed.isEmpty()) {
                prefs.remove(key)
                prefs.remove(urlKey)
            } else {
                prefs[key] = trimmed
                prefs[urlKey] = baseUrl
            }
            // Any per-provider write for Venice supersedes the
            // single-provider era's key — drop it so remove actually removes.
            if (providerId == LEGACY_CLOUD_PROVIDER) prefs.remove(KEY_CLOUD_API_KEY)
        }
    }

    /**
     * The `baseUrl` each saved cloud API key was saved for, keyed by provider
     * id — where the user agreed to send that key. Keys saved before these
     * were recorded (1.1.0 and earlier) have none until
     * [recordCloudApiKeyBaseUrls] fills it in on the next provider load.
     */
    open val cloudApiKeyBaseUrls: Flow<Map<String, String>> = dataStore.data.map { prefs ->
        val urls = mutableMapOf<String, String>()
        for ((key, value) in prefs.asMap()) {
            val providerId = key.name.removePrefix(CLOUD_API_KEY_BASE_URL_PREFIX)
            if (providerId != key.name && value is String && value.isNotBlank()) {
                urls[providerId] = value
            }
        }
        urls
    }

    /**
     * Trust-on-first-use migration for keys that predate
     * [cloudApiKeyBaseUrls]: record [urls] (provider id → its current
     * `baseUrl`) for providers that have no recorded URL yet. Never
     * overwrites one.
     */
    open suspend fun recordCloudApiKeyBaseUrls(urls: Map<String, String>) {
        dataStore.edit { prefs ->
            for ((providerId, url) in urls) {
                val urlKey = stringPreferencesKey("$CLOUD_API_KEY_BASE_URL_PREFIX$providerId")
                if (prefs[urlKey].isNullOrBlank()) prefs[urlKey] = url
            }
        }
    }

    /**
     * Measured time-to-first-audio samples, keyed by
     * [app.marmalade.tts.data.latencyKeyFor]. Values are the raw
     * milliseconds of the last few runs, newest last.
     *
     * Stored as one comma-separated string per key rather than a structured
     * blob: Preferences has no list type, the window is ~10 small integers,
     * and a malformed entry should degrade to "no data" rather than take the
     * settings file down — hence the lenient parse.
     */
    open val latencySamples: Flow<Map<String, List<Int>>> = dataStore.data.map { prefs ->
        val out = mutableMapOf<String, List<Int>>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name.removePrefix(LATENCY_PREFIX)
            if (name == key.name || value !is String) continue
            val samples = value.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (samples.isNotEmpty()) out[name] = samples
        }
        out
    }

    /** Append one sample for [key], keeping at most [keep] most-recent. */
    open suspend fun recordLatencySample(key: String, millis: Int, keep: Int) {
        dataStore.edit { prefs ->
            val prefKey = stringPreferencesKey("$LATENCY_PREFIX$key")
            val existing = prefs[prefKey]
                ?.split(',')
                ?.mapNotNull { it.trim().toIntOrNull() }
                .orEmpty()
            prefs[prefKey] = (existing + millis).takeLast(keep).joinToString(",")
        }
    }

    /**
     * Take one of [key]'s [perWeek] sampling slots for [week], returning
     * false when the week's budget is already spent.
     *
     * Claim-and-check in a single `edit` so two concurrent utterances can't
     * both read the same count and both spend the last slot; DataStore
     * serialises the transform.
     */
    open suspend fun claimLatencyQuota(key: String, week: Long, perWeek: Int): Boolean {
        var claimed = false
        dataStore.edit { prefs ->
            val prefKey = stringPreferencesKey("$LATENCY_QUOTA_PREFIX$key")
            val parts = prefs[prefKey]?.split(':')
            val storedWeek = parts?.getOrNull(0)?.toLongOrNull()
            val used = if (storedWeek == week) parts?.getOrNull(1)?.toIntOrNull() ?: 0 else 0
            if (used >= perWeek) return@edit
            claimed = true
            prefs[prefKey] = "$week:${used + 1}"
        }
        return claimed
    }

    /**
     * The one-off Kitten benchmark result, or null when it hasn't run on
     * this install yet. Written exactly once by
     * [app.marmalade.tts.perf.DeviceCapability]; the timestamp rides along
     * so a future app version can decide a stored measurement is too old to
     * trust (e.g. after an engine bundle rev changes the workload).
     *
     * Both halves are read as one atomic snapshot — a value without a
     * timestamp is treated as absent rather than half-trusted.
     */
    open val kittenRtfMeasurement: Flow<KittenRtfMeasurement?> = dataStore.data.map { prefs ->
        val rtf = prefs[KEY_KITTEN_RTF] ?: return@map null
        val at = prefs[KEY_KITTEN_RTF_AT] ?: return@map null
        KittenRtfMeasurement(rtf = rtf, measuredAtMillis = at)
    }

    /** Records the device benchmark. Overwrites any previous measurement. */
    open suspend fun setKittenRtfMeasurement(rtf: Double, measuredAtMillis: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_KITTEN_RTF] = rtf
            prefs[KEY_KITTEN_RTF_AT] = measuredAtMillis
        }
    }

    /** Forgets the device benchmark, so the next probe measures again. */
    open suspend fun clearKittenRtfMeasurement() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_KITTEN_RTF)
            prefs.remove(KEY_KITTEN_RTF_AT)
        }
    }

    /**
     * Rolling measured warm RTF per engine name (rendered-audio seconds ÷
     * wall-clock render time), fed by the synth service on every completed
     * utterance. Drives the speed-up performance warning once a few
     * utterances have accrued; before that the warning runs on
     * [app.marmalade.tts.perf.EngineRecommender]'s probe prediction.
     *
     * Purely derived data — safe to drop, and it repopulates from use — so
     * it is deliberately not part of the semver-frozen settings surface.
     * One key per engine, mirroring the latency window's per-model layout.
     *
     * Values measured by a different app version are stale (engine code
     * changes between releases: 1.2's streaming made Pocket several times
     * faster), so they read as absent and the next [recordEngineRtf] wipes
     * them. An engine's own value goes when its files change
     * ([clearEngineRtf]).
     */
    open val engineRtf: Flow<Map<String, Double>> = dataStore.data.map { prefs ->
        if (prefs[KEY_ENGINE_RTF_VERSION_CODE] != appVersionCode) return@map emptyMap()
        val out = mutableMapOf<String, Double>()
        for ((key, value) in prefs.asMap()) {
            val name = key.name.removePrefix(ENGINE_RTF_PREFIX)
            if (name != key.name && value is Double) out[name] = value
        }
        out
    }

    /**
     * Fold one measured RTF [sampleRtf] for [engineName], rendering in
     * [language] (the phonemizer language, null for the engine's default),
     * into its rolling average (see [RollingRtf]). Read-modify-write inside
     * a single DataStore edit so concurrent utterances can't clobber each
     * other's update.
     *
     * The average restarts when the language changes: one engine renders
     * languages at different speeds, and a blend of two would flip the
     * speed warning and chunk mode (#54) back and forth between reads.
     */
    open suspend fun recordEngineRtf(engineName: String, sampleRtf: Double, language: String? = null) {
        dataStore.edit { prefs ->
            if (prefs[KEY_ENGINE_RTF_VERSION_CODE] != appVersionCode) {
                prefs.asMap().keys
                    .filter { it.name.startsWith(ENGINE_RTF_PREFIX) }
                    .forEach { prefs.remove(it) }
                prefs[KEY_ENGINE_RTF_VERSION_CODE] = appVersionCode
            }
            val key = doublePreferencesKey("$ENGINE_RTF_PREFIX$engineName")
            val languageKey = stringPreferencesKey("$ENGINE_RTF_LANGUAGE_PREFIX$engineName")
            val lang = language.orEmpty()
            val previous = if (prefs[languageKey].orEmpty() == lang) prefs[key] else null
            prefs[key] = RollingRtf.update(previous, sampleRtf)
            prefs[languageKey] = lang
        }
    }

    /**
     * Drop [engineName]'s measured RTF: its bundle or a voice pack was just
     * installed or updated, and the new files may run at a different speed.
     * The warning falls back to the probe prediction until it is re-measured.
     */
    open suspend fun clearEngineRtf(engineName: String) {
        dataStore.edit { prefs ->
            prefs.remove(doublePreferencesKey("$ENGINE_RTF_PREFIX$engineName"))
            prefs.remove(stringPreferencesKey("$ENGINE_RTF_LANGUAGE_PREFIX$engineName"))
        }
    }

    /**
     * Reader-mode background preset, stored as a
     * [app.marmalade.tts.ui.reader.ReaderBackground] name.
     *
     * Emits `null` until the user picks one — the reader then follows the
     * app's own light/dark instead (see `ReaderDisplayPrefs.resolveBackground`).
     * Deliberately not defaulted here: baking a value in at first read would
     * pin a user who later switches the app to dark onto a white page.
     *
     * These are display settings, not article content — the reader's
     * "nothing about a fetched page is persisted" rule covers the article.
     */
    open val readerBackground: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_READER_BACKGROUND]
    }

    /** Persist the reader background preset (a `ReaderBackground.name`). */
    open suspend fun setReaderBackground(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_BACKGROUND] = value
        }
    }

    /**
     * Reader-mode font family, stored as a
     * [app.marmalade.tts.ui.reader.ReaderFont] name. `null` ⇒ Sans, the app's
     * own face; the caller applies that default.
     */
    open val readerFont: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_READER_FONT]
    }

    /** Persist the reader font family (a `ReaderFont.name`). */
    open suspend fun setReaderFont(value: String) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_FONT] = value
        }
    }

    /**
     * Reader-mode body text size in sp. `null` ⇒ the theme's body size; the
     * caller applies that default and clamps to the supported range.
     */
    open val readerFontSizeSp: Flow<Int?> = dataStore.data.map { prefs ->
        prefs[KEY_READER_FONT_SIZE]
    }

    /** Persist the reader body text size, in sp. */
    open suspend fun setReaderFontSizeSp(value: Int) {
        dataStore.edit { prefs ->
            prefs[KEY_READER_FONT_SIZE] = value
        }
    }

    /** Persist the show-developer-engines toggle. */
    open suspend fun setShowDeveloperEngines(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_DEVELOPER_ENGINES] = value
        }
    }

    companion object {
        // Stable key names — part of the v1.0 public surface per SPEC.md's
        // "settings keys all frozen per semver" line. Don't rename.
        private val KEY_DEFAULT_VOICE_ID = stringPreferencesKey("default_voice_id")

        // Onboarding completion flag — part of the same stability contract.
        // Removing this would cause every existing install to re-run
        // onboarding after an update; renaming would do the same.
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        private val KEY_BAKED_DEFAULT_SEEDED = booleanPreferencesKey("baked_default_seeded")

        // Theme preset name (stored as ThemePreset.name, not ordinal — see
        // [themePreset] kdoc).
        private val KEY_THEME_PRESET = stringPreferencesKey("theme_preset")

        // Dark-mode override: "system" / "light" / "dark". Decoupled from
        // theme preset so the user can pick "Marmalade + always dark" etc.
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

        // Keep-engine-loaded toggle; default true preserves pre-toggle behavior.
        private val KEY_KEEP_LOADED = booleanPreferencesKey("keep_engine_loaded")

        // Primary alias pointer (nullable). Null is encoded as
        // "key absent from DataStore" — see [setPrimaryAliasId].
        // Points at VoiceAlias.id since db v10. Deliberately a NEW key
        // rather than a reused one: the old "primary_alias_name" holds a
        // display name, and a stale name silently resolving to nothing is
        // exactly the failure v10 set out to remove. DataStore survives the
        // destructive DB step, so the old key may still be on disk; nothing
        // reads it, and a pointer at a wiped alias is already handled as
        // "no primary" by every caller.
        private val KEY_PRIMARY_ALIAS_ID = stringPreferencesKey("primary_alias_id")

        /**
         * English defaults advertised to system TTS clients, so a request
         * still stamped with a previous primary alias's voice is recognized
         * as an auto-fill echo rather than a deliberate pick — see
         * [advertisedDefaultVoiceIds].
         */
        private val KEY_ADVERTISED_DEFAULT_VOICE_IDS =
            stringSetPreferencesKey("advertised_default_voice_ids")

        // Last-seeded catalog version. v0.1.19 introduces this so that
        // expanding a voice catalog (e.g. Kokoro 11 → 53 voices for
        // multi-lang) automatically re-seeds existing installs whose DB
        // still has the pre-expansion rows.
        private val KEY_CATALOG_VERSION = intPreferencesKey("catalog_version")

        // Last "What's new" notes version seen (#41, 1.2). Absent on every
        // install from before 1.2. Stable key: renaming it would show the
        // current notes to everyone again.
        private val KEY_WHATS_NEW_SEEN_VERSION = intPreferencesKey("whats_new_seen_version")

        // Manual ONNX-Runtime intra-op thread count. Absent / ≤0 ⇒ auto-
        // detect per CPU cluster. Cross-device: hardcoded 6 fits Tensor
        // G3 well but spills onto efficiency cores on Snapdragon Gen 2/3
        // and Exynos 2400, regressing 5-20% per ORT bench.
        private val KEY_INTRA_OP_THREADS = intPreferencesKey("intra_op_threads")

        // Show the legacy sherpa engines in the engine lists. Absent ⇒
        // BuildConfig.DEBUG (shown in debug, hidden in release). v0.3.0-alpha.10.Z.
        private val KEY_SHOW_DEVELOPER_ENGINES = booleanPreferencesKey("show_developer_engines")

        // Cloud API engine keys, one pref per provider:
        // "cloud_api_key_<providerId>". No provider keyed => unconfigured.
        private const val CLOUD_API_KEY_PREFIX = "cloud_api_key_"

        // The baseUrl each cloud key was saved for, "cloud_key_base_url_<id>".
        // Must not start with CLOUD_API_KEY_PREFIX: [cloudApiKeys] scans by
        // prefix and would read these as keys of a provider "url_<id>".
        private const val CLOUD_API_KEY_BASE_URL_PREFIX = "cloud_key_base_url_"

        // Cloud disclaimer acceptance. Absent ⇒ never shown, so existing
        // installs that already keyed a provider see it once on next visit.
        private val KEY_CLOUD_DISCLAIMER_ACCEPTED =
            booleanPreferencesKey("cloud_disclaimer_accepted")

        // Measured latency window, one key per model. Purely derived data —
        // safe to drop, and it repopulates itself from use.
        private const val LATENCY_PREFIX = "voice_latency_"

        // "<weekIndex>:<samplesTaken>" per model. A stale week is simply
        // overwritten, so nothing needs sweeping. Deliberately NOT prefixed
        // with LATENCY_PREFIX — [latencySamples] scans by prefix, and a
        // quota key nested under it would be picked up as a sample list.
        private const val LATENCY_QUOTA_PREFIX = "latency_quota_"

        // Single-provider era key (v0.3.0-alpha.11 dev builds); read as
        // Venice's key until a per-provider write supersedes it.
        private val KEY_CLOUD_API_KEY = stringPreferencesKey("cloud_api_key")
        private const val LEGACY_CLOUD_PROVIDER = "venice"

        // Device benchmark (Feature P). Derived data — safe to drop; the
        // benchmark just re-runs. Deliberately NOT semver-frozen for that
        // reason.
        private val KEY_KITTEN_RTF = doublePreferencesKey("device_kitten_rtf")
        private val KEY_KITTEN_RTF_AT = longPreferencesKey("device_kitten_rtf_at")

        // Rolling per-engine measured warm RTF, one key per engine. Derived
        // data — safe to drop; repopulates from use — so not semver-frozen.
        private const val ENGINE_RTF_PREFIX = "engine_rtf_"

        // The language each engine's stored RTF was measured in (#54). Starts
        // with ENGINE_RTF_PREFIX so the version sweep takes it too; it holds
        // a String, so [engineRtf] skips it.
        private const val ENGINE_RTF_LANGUAGE_PREFIX = "${ENGINE_RTF_PREFIX}language_"

        // The versionCode that measured the stored engine RTFs (#40); any
        // other value marks them stale. Deliberately NOT prefixed with
        // ENGINE_RTF_PREFIX, which [recordEngineRtf]'s sweep removes.
        private val KEY_ENGINE_RTF_VERSION_CODE = intPreferencesKey("measured_rtf_version_code")

        // Reader display settings (step G). All three are absent until the
        // user changes them; the reader applies its own defaults on read.
        private val KEY_READER_BACKGROUND = stringPreferencesKey("reader_background")
        private val KEY_READER_FONT = stringPreferencesKey("reader_font")
        private val KEY_READER_FONT_SIZE = intPreferencesKey("reader_font_size_sp")

        // P-K — keepalive mode, stored as KeepaliveMode.name string.
        // Absent ⇒ Smart (default). Stable key; semver-protected.
        private val KEY_KEEPALIVE_MODE = stringPreferencesKey("keepalive_mode")
    }
}
