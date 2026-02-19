package io.kortex.agent.sanitization

/**
 * Sanitizes HTTP headers by masking values of sensitive headers
 * to prevent credentials, tokens, and session data from leaking
 * into telemetry spans.
 */
object HeaderSanitizer {

    private const val REDACTED = "[REDACTED]"

    /**
     * Set of header names (lower-cased) whose values must never appear
     * in telemetry data.
     */
    private val SENSITIVE_HEADERS: Set<String> = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "proxy-authorization",
        "x-api-key",
        "x-auth-token"
    )

    /**
     * Returns `true` if the given header name is on the blacklist.
     */
    fun isSensitive(headerName: String): Boolean =
        headerName.lowercase() in SENSITIVE_HEADERS

    /**
     * Returns the header value as-is if the header is safe, or [REDACTED]
     * if the header is on the sensitive blacklist.
     *
     * @param headerName  the HTTP header name (case-insensitive comparison)
     * @param headerValue the raw header value
     * @return the original value or `[REDACTED]`
     */
    fun sanitize(headerName: String, headerValue: String): String =
        if (isSensitive(headerName)) REDACTED else headerValue
}
