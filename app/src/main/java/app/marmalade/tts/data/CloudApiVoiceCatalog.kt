package app.marmalade.tts.data

import app.marmalade.tts.data.cloud.AzureSpeech
import app.marmalade.tts.data.cloud.CloudApiStyle
import app.marmalade.tts.data.cloud.CloudModel
import app.marmalade.tts.data.cloud.CloudProvider
import app.marmalade.tts.data.db.VoiceMeta

/**
 * Identity + id scheme for the Cloud API engine — hosted synthesis over an
 * OpenAI-compatible `/audio/speech` endpoint or Azure AI Speech's SSML
 * endpoint (the provider's [CloudApiStyle] picks which). Nothing runs
 * on-device, so there is no bundle, no download and no entry in
 * [app.marmalade.tts.install.EngineCatalog]; the engine is "installed"
 * when any provider has an API key configured.
 *
 * Unlike the local engines there is no static voice list here: the rows
 * come from provider descriptors + live discovery
 * ([app.marmalade.tts.data.cloud.CloudProviderStore]) and are written to
 * Room at sync time, so a provider adding a model or voices never needs
 * an app release.
 *
 * Voice id scheme: `cloud-api-v1:<provider>:<model>:<voice>` — the
 * provider + model ride inside the id so aliases, per-app routing and the
 * system-TTS voiceName token all keep working with zero schema changes.
 * The voice segment is percent-escaped for `:` and `%` ([voiceId] /
 * [parseVoiceId]): Azure's HD voices are named `en-US-Ava:DragonHDLatestNeural`,
 * and a raw colon there would split the id into five parts.
 */
object CloudApiVoiceCatalog {

    const val ENGINE = "cloud-api-v1"

    /**
     * Fallback rate for cloud voices whose model carries no declared rate.
     *
     * NOT the engine's rate — cloud models emit whatever they emit (Venice
     * serves 24 kHz, 32 kHz, 44.1 kHz and 48 kHz across its catalog), so the
     * real rate travels per-voice in [VoiceMeta.sampleRate], sourced from
     * [CloudModel.sampleRate]. This constant only covers a descriptor entry
     * that omits the field.
     */
    const val SAMPLE_RATE = CloudModel.DEFAULT_SAMPLE_RATE

    /**
     * User-facing engine label (picker section header, alias editor).
     *
     * The label names the engine, not one vendor: the same engine speaks the
     * OpenAI `/audio/speech` shape and Azure's SSML endpoint, and a user
     * picking "Cloud voices" sees whichever providers they have configured.
     *
     * [ENGINE] is the persisted key and must never change — it is baked
     * into every voice id, alias, per-app route and system-TTS voice token.
     * This constant is display-only and free to reword.
     */
    const val DISPLAY_NAME = "Cloud voices"

    /** A cloud voice id, decomposed. */
    data class CloudVoiceRef(
        val providerId: String,
        val modelId: String,
        val voice: String,
    )

    fun voiceId(providerId: String, modelId: String, voice: String): String =
        "$ENGINE:$providerId:$modelId:${escapeVoice(voice)}"

    /**
     * Decompose a cloud voice id. Ids from the pre-provider era
     * (`cloud-api-v1:af_heart`, seeded by CATALOG_VERSION 25) resolve to
     * the provider+model that era was hardcoded to, so aliases created
     * against them keep speaking. Returns null for ids of other engines.
     */
    fun parseVoiceId(voiceId: String): CloudVoiceRef? {
        val parts = voiceId.split(':')
        if (parts.firstOrNull() != ENGINE) return null
        return when (parts.size) {
            2 -> CloudVoiceRef("venice", "tts-kokoro", unescapeVoice(parts[1]))
            4 -> CloudVoiceRef(parts[1], parts[2], unescapeVoice(parts[3]))
            else -> null
        }
    }

    /**
     * Build the Room row for one (provider, model, voice).
     *
     * A provider that discloses voice metadata ([CloudModel.voiceInfo],
     * Azure today) supplies the display name, language and gender directly.
     * Otherwise Kokoro-style voice keys (`af_heart`) carry language + gender
     * in their `<lang><gender>_` prefix — reuse the KokoroDirect helpers for
     * those — and an Azure ShortName without discovery metadata still yields
     * its locale (`en-US-JennyNeural` → en-US). The shape check matters:
     * blindly deriving from the first letters would tag OpenAI's `ballad`
     * en-GB and `echo` es-ES.
     */
    fun voiceMeta(provider: CloudProvider, model: CloudModel, voice: String): VoiceMeta {
        val info = model.voiceInfo[voice]
        if (info != null) {
            return VoiceMeta(
                id = voiceId(provider.id, model.id, voice),
                engine = ENGINE,
                displayName = info.displayName.ifBlank { voice },
                languageCode = info.languageCode.ifBlank { "en-US" },
                sampleRate = model.sampleRate,
                gender = info.gender,
                isInstalled = false,
            )
        }
        val kokoroStyle = isKokoroStyle(voice)
        val azureStyle = provider.api == CloudApiStyle.AZURE
        return VoiceMeta(
            id = voiceId(provider.id, model.id, voice),
            engine = ENGINE,
            displayName = if (kokoroStyle) KokoroDirectVoiceCatalog.prettyName(voice) else voice,
            languageCode = (if (kokoroStyle) KokoroDirectVoiceCatalog.languageFor(voice) else null)
                ?: (if (azureStyle) AzureSpeech.localeOf(voice) else null)
                ?: "en-US",
            sampleRate = model.sampleRate,
            gender = if (kokoroStyle) KokoroDirectVoiceCatalog.genderFor(voice) else null,
            isInstalled = false,
        )
    }

    private fun isKokoroStyle(voice: String): Boolean =
        voice.length > 2 && voice[2] == '_' && (voice[1] == 'f' || voice[1] == 'm')

    /**
     * Whether a cloud voice row's `languageCode` is the one language the voice
     * speaks. Kokoro-style keys carry a language, and so do Azure ShortNames
     * (`en-US-JennyNeural`, and a multilingual VoiceMeta's `en-US` is then a
     * real default locale, not a placeholder). Every other cloud voice
     * (OpenAI's `alloy`, …) is multilingual and its row says `en-US` only
     * because the column needs a value — the pickers still file it under
     * English. So language routing ([app.marmalade.tts.lang.VoiceForLanguage])
     * must treat such a voice's language as unknown, not as English only.
     */
    fun hasKnownLanguage(voiceId: String): Boolean =
        parseVoiceId(voiceId)?.let {
            isKokoroStyle(it.voice) || AzureSpeech.isAzureVoice(it.voice)
        } ?: false

    /** Percent-escape the voice segment so a `:` or `%` can't split the id. */
    internal fun escapeVoice(voice: String): String =
        voice.replace("%", "%25").replace(":", "%3A")

    /** Inverse of [escapeVoice]; ids written before it contain no escapes. */
    internal fun unescapeVoice(encoded: String): String =
        PERCENT_ESCAPE.replace(encoded) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

    private val PERCENT_ESCAPE = Regex("%([0-9A-Fa-f]{2})")

    /**
     * Short provenance line for a cloud voice row ("Venice · Kokoro"),
     * shown in the picker so a voice that exists under two providers or
     * models stays distinguishable. Falls back to raw ids for a voice
     * whose provider/model isn't in [providers] (e.g. removed remotely).
     */
    fun provenance(voiceId: String, providers: List<CloudProvider>): String? {
        val ref = parseVoiceId(voiceId) ?: return null
        val provider = providers.firstOrNull { it.id == ref.providerId }
        val model = provider?.models?.firstOrNull { it.id == ref.modelId }
        return "${provider?.displayName ?: ref.providerId} · ${model?.displayName ?: ref.modelId}"
    }
}
