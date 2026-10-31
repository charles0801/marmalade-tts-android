package app.marmalade.tts.data.cloud

/**
 * The wire protocol a [CloudProvider] speaks.
 *
 * Providers are data (`cloud-providers.json`), but the shape of a synthesis
 * request is not: each style names a code path in
 * [app.marmalade.tts.engine.api.CloudApiEngine]. A descriptor picks one with
 * its `"api"` field (absent = [OPENAI], the original shape).
 *
 * @property OPENAI OpenAI-compatible `POST {baseUrl}/audio/speech`: a JSON
 *   body, `Authorization: Bearer`, voices listed by `GET
 *   {baseUrl}/models?type=tts`.
 * @property AZURE Azure AI Speech REST: an SSML body to
 *   `{endpoint}/cognitiveservices/v1`, `Ocp-Apim-Subscription-Key`, voices
 *   listed by `GET {endpoint}/cognitiveservices/voices/list`. The endpoint is
 *   regional (`https://<region>.tts.speech.microsoft.com`), so the descriptor
 *   carries a [AzureSpeech.REGION_TOKEN] template and the user supplies the
 *   region with their key.
 */
enum class CloudApiStyle {
    OPENAI,
    AZURE;

    companion object {
        /**
         * Parse a descriptor's `"api"` value. Absent or blank means
         * [OPENAI]; an unknown value returns null so the document parser can
         * reject the whole document (fail closed, like a cleartext URL — an
         * app build that can't speak a protocol must not half-apply it).
         */
        fun parse(value: String?): CloudApiStyle? = when (value?.trim()?.lowercase()) {
            null, "" -> OPENAI
            "openai" -> OPENAI
            "azure" -> AZURE
            else -> null
        }
    }
}

/**
 * Azure Speech's regional endpoint and voice-name conventions.
 *
 * Kept here rather than in the engine because the voice catalog also needs
 * them: a voice row's language and the descriptor's site pin both depend on
 * how Azure names its endpoints and voices.
 */
object AzureSpeech {

    /**
     * Where a provider's `baseUrl` puts the user's region, e.g.
     * `https://{region}.tts.speech.microsoft.com`. Such a `baseUrl` is a
     * template, not a request URL, until [CloudProvider.resolvedBaseUrl]
     * supplies the region.
     */
    const val REGION_TOKEN = "{region}"

    /**
     * Azure region names are lowercase alphanumerics (`eastus`,
     * `germanywestcentral`, `usgovarizona`) and become a DNS label, so the
     * set is deliberately narrow: anything else must not reach a URL.
     */
    private val REGION = Regex("^[a-z0-9]{2,32}$")

    fun normalizedRegion(region: String): String = region.trim().lowercase()

    fun isValidRegion(region: String): Boolean = REGION.matches(normalizedRegion(region))

    /** The regional endpoint for [region] from a [REGION_TOKEN] template. */
    fun endpointFor(template: String, region: String): String =
        template.replace(REGION_TOKEN, normalizedRegion(region))

    /**
     * The region in a regional Azure endpoint, or null when [url] is not one:
     * `https://eastus.tts.speech.microsoft.com` → `eastus`. Used to prefill
     * the region field when the user changes an existing key.
     */
    fun regionFromBaseUrl(url: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val labels = host.lowercase().split('.')
        return if (labels.size >= 3 && labels[1] == "tts" && labels[2] == "speech") {
            labels[0]
        } else {
            null
        }
    }

    /**
     * An Azure voice ShortName's locale: `en-US-JennyNeural` → `en-US`,
     * `zh-CN-liaoning-XiaobeiNeural` → `zh-CN` (the dialect comes after the
     * locale). Null for a name that doesn't start with a language-region pair.
     */
    fun localeOf(voice: String): String? =
        SHORT_NAME.matchEntire(voice)?.groupValues?.get(1)

    /** True for an Azure voice ShortName (see [localeOf]). */
    fun isAzureVoice(voice: String): Boolean = SHORT_NAME.matches(voice)

    private val SHORT_NAME = Regex("^([A-Za-z]{2,3}-[A-Za-z]{2,4})-.+")
}
