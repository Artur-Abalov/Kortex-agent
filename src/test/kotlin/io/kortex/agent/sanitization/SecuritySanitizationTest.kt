package io.kortex.agent.sanitization

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Test suite verifying that telemetry spans never contain sensitive data.
 * Covers SQL value masking and HTTP header blacklisting.
 */
class SecuritySanitizationTest {

    // ── SQL Sanitization ─────────────────────────────────────────────────────

    @Test
    fun `single-quoted string literals are replaced with placeholder`() {
        val dirty = "SELECT * FROM users WHERE email = 'secret@link.com'"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("SELECT * FROM users WHERE email = ?", clean)
    }

    @Test
    fun `multiple string literals are all replaced`() {
        val dirty = "SELECT * FROM users WHERE name = 'Alice' AND city = 'Berlin'"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("SELECT * FROM users WHERE name = ? AND city = ?", clean)
    }

    @Test
    fun `numeric literals are replaced with placeholder`() {
        val dirty = "SELECT * FROM orders WHERE amount > 100 AND id = 42"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("SELECT * FROM orders WHERE amount > ? AND id = ?", clean)
    }

    @Test
    fun `INSERT values are masked`() {
        val dirty = "INSERT INTO users (name, age) VALUES ('Bob', 30)"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("INSERT INTO users (name, age) VALUES (?, ?)", clean)
    }

    @Test
    fun `boolean literals are replaced`() {
        val dirty = "UPDATE users SET active = true WHERE deleted = false"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("UPDATE users SET active = ? WHERE deleted = ?", clean)
    }

    @Test
    fun `NULL literals are replaced`() {
        val dirty = "SELECT * FROM users WHERE deleted_at IS NOT NULL"
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("SELECT * FROM users WHERE deleted_at IS NOT ?", clean)
    }

    @Test
    fun `double-quoted string literals are replaced`() {
        val dirty = """SELECT * FROM users WHERE name = "Alice""""
        val clean = SqlSanitizer.sanitize(dirty)
        assertEquals("SELECT * FROM users WHERE name = ?", clean)
    }

    @Test
    fun `null input returns null`() {
        assertNull(SqlSanitizer.sanitize(null))
    }

    @Test
    fun `blank input returns blank`() {
        assertEquals("  ", SqlSanitizer.sanitize("  "))
    }

    @Test
    fun `query with no literals is unchanged`() {
        val sql = "SELECT * FROM users WHERE id = ?"
        assertEquals(sql, SqlSanitizer.sanitize(sql))
    }

    // ── Header Sanitization ──────────────────────────────────────────────────

    @Test
    fun `Authorization header is redacted`() {
        val value = HeaderSanitizer.sanitize("Authorization", "Bearer eyJhbGci...")
        assertEquals("[REDACTED]", value)
    }

    @Test
    fun `authorization header is case-insensitive`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("authorization", "Basic abc123"))
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("AUTHORIZATION", "Basic abc123"))
    }

    @Test
    fun `Cookie header is redacted`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("Cookie", "session=abc123"))
    }

    @Test
    fun `Set-Cookie header is redacted`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("Set-Cookie", "id=a3fWa; Path=/"))
    }

    @Test
    fun `Proxy-Authorization header is redacted`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("Proxy-Authorization", "Basic dXNlcjpw"))
    }

    @Test
    fun `X-Api-Key header is redacted`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("X-Api-Key", "sk-12345abcdef"))
    }

    @Test
    fun `X-Auth-Token header is redacted`() {
        assertEquals("[REDACTED]", HeaderSanitizer.sanitize("X-Auth-Token", "token-value"))
    }

    @Test
    fun `safe headers are passed through`() {
        assertEquals("application/json", HeaderSanitizer.sanitize("Content-Type", "application/json"))
        assertEquals("gzip", HeaderSanitizer.sanitize("Accept-Encoding", "gzip"))
        assertEquals("en-US", HeaderSanitizer.sanitize("Accept-Language", "en-US"))
    }

    @Test
    fun `isSensitive correctly identifies sensitive headers`() {
        assertTrue(HeaderSanitizer.isSensitive("Authorization"))
        assertTrue(HeaderSanitizer.isSensitive("Cookie"))
        assertTrue(HeaderSanitizer.isSensitive("Set-Cookie"))
        assertFalse(HeaderSanitizer.isSensitive("Content-Type"))
        assertFalse(HeaderSanitizer.isSensitive("Accept"))
    }
}
