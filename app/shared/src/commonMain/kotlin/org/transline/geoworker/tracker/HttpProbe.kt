package org.transline.geoworker.tracker

/**
 * HTTP probe helpers (KMP-only). Method allowlist GET|PATCH|PUT|POST (D-02).
 * Header merge: custom then Bearer wins (D-07, D-09).
 * URL redaction for event payloads (D-03, D-04, T-01-01).
 */
object HttpProbe {
    const val MAX_BODY_BYTES = 256 * 1024
    const val MAX_URL_CHARS = 512

    private val ALLOWED_METHODS = setOf("GET", "PATCH", "PUT", "POST")

    private val SENSITIVE_QUERY_KEYS = setOf(
        "access_token",
        "refresh_token",
        "token",
        "authorization",
        "auth",
        "password",
        "secret",
    )

    fun isAllowedMethod(method: String): Boolean =
        method.uppercase() in ALLOWED_METHODS

    fun normalizeMethod(method: String): String = method.uppercase()

    /**
     * Merge custom headers then force Authorization Bearer access.
     */
    fun buildProbeHeaders(customHeaders: Map<String, String>, accessToken: String): Map<String, String> {
        val merged = customHeaders.toMutableMap()
        merged["Authorization"] = "Bearer $accessToken"
        return merged
    }

    fun isBodyTooLarge(body: String?): Boolean {
        if (body == null) return false
        return body.encodeToByteArray().size > MAX_BODY_BYTES
    }

    /**
     * Redact sensitive query keys and truncate for safe event emission.
     */
    fun redactUrl(url: String): String {
        val withoutSensitive = redactSensitiveQueryParams(url)
        return if (withoutSensitive.length <= MAX_URL_CHARS) {
            withoutSensitive
        } else {
            withoutSensitive.take(MAX_URL_CHARS) + "…"
        }
    }

    internal fun redactSensitiveQueryParams(url: String): String {
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return url
        val base = url.substring(0, qIndex)
        val query = url.substring(qIndex + 1)
        if (query.isEmpty()) return url
        val kept = query.split('&').mapNotNull { pair ->
            if (pair.isEmpty()) return@mapNotNull null
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            if (key.lowercase() in SENSITIVE_QUERY_KEYS) null else pair
        }
        return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
    }
}

data class HttpProbeResult(
    val ok: Boolean,
    val method: String,
    val url: String,
    val status: Int?,
    val message: String,
)

object SecureConfigEventType {
    const val KEYCHAIN_SAVED = "KEYCHAIN_SAVED"
    const val KEYCHAIN_CLEARED = "KEYCHAIN_CLEARED"
    const val KEYCHAIN_ERROR = "KEYCHAIN_ERROR"
    const val AUTH_MISSING = "AUTH_MISSING"
    const val HTTP_OK = "HTTP_OK"
    const val HTTP_FAILED = "HTTP_FAILED"
}
