package io.kortex.agent.sanitization

/**
 * Sanitizes SQL queries by replacing literal values with placeholders
 * to prevent PII and sensitive data from leaking into telemetry spans.
 *
 * Example:
 *   SELECT * FROM users WHERE email = 'secret@link.com'
 *   → SELECT * FROM users WHERE email = ?
 */
object SqlSanitizer {

    // Matches single-quoted string literals (handles escaped quotes inside)
    private val SINGLE_QUOTED = Regex("'(?:[^'\\\\]|\\\\.)*'")

    // Matches double-quoted string literals
    private val DOUBLE_QUOTED = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")

    // Matches numeric literals (integers and decimals, including negative)
    private val NUMERIC = Regex("\\b-?\\d+(?:\\.\\d+)?\\b")

    // Matches boolean literals
    private val BOOLEAN = Regex("\\b(?:TRUE|FALSE|true|false)\\b")

    // Matches NULL literals
    private val NULL_LITERAL = Regex("\\bNULL\\b", RegexOption.IGNORE_CASE)

    /**
     * Replaces all literal values in the given SQL query with `?` placeholders.
     *
     * @param sql the raw SQL query string
     * @return the sanitized SQL with all literal values replaced by `?`
     */
    fun sanitize(sql: String?): String? {
        if (sql.isNullOrBlank()) return sql

        var result = sql
        // Order matters: replace quoted strings first to avoid partial matches
        result = SINGLE_QUOTED.replace(result, "?")
        result = DOUBLE_QUOTED.replace(result, "?")
        result = BOOLEAN.replace(result, "?")
        result = NULL_LITERAL.replace(result, "?")
        result = NUMERIC.replace(result, "?")
        return result
    }
}
