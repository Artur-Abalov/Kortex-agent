package io.kortex.agent.integration

import java.sql.DriverManager
import java.sql.SQLException

/**
 * Minimal test application executed as a child JVM process by [AgentIntegrationTest].
 *
 * The Kortex agent is loaded via `-javaagent` in the child process, so every
 * [java.sql.PreparedStatement] execute call is automatically instrumented.
 *
 * Scenarios (passed as the first command-line argument):
 * - `success` – execute a simple `SELECT 1` query (happy path).
 * - `error`   – trigger a duplicate-key violation so the agent captures an
 *               error span while the span is still sent.
 */
object JdbcTestApp {

    @JvmStatic
    fun main(args: Array<String>) {
        // Give the agent a moment to finish its own initialisation
        Thread.sleep(500)

        val scenario = args.firstOrNull() ?: "success"

        when (scenario) {
            "success" -> runSuccess()
            "error"   -> runError()
            else      -> System.err.println("[JdbcTestApp] Unknown scenario: $scenario")
        }

        // Wait long enough for the agent's async reporter to flush the span batch
        Thread.sleep(3000)
    }

    private fun runSuccess() {
        val conn = DriverManager.getConnection("jdbc:h2:mem:success_test;DB_CLOSE_DELAY=-1")
        val stmt = conn.prepareStatement("SELECT 1")
        stmt.executeQuery()
        stmt.close()
        conn.close()
    }

    private fun runError() {
        val conn = DriverManager.getConnection("jdbc:h2:mem:error_test;DB_CLOSE_DELAY=-1")

        // Create a table with a primary-key constraint so we can provoke a
        // duplicate-key error at *execution* time (not at prepare time).
        conn.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS dup_test (id INT PRIMARY KEY)")
        }

        val stmt = conn.prepareStatement("INSERT INTO dup_test (id) VALUES (?)")
        stmt.setInt(1, 1)
        stmt.executeUpdate() // first insert succeeds

        stmt.setInt(1, 1)   // same primary key → will violate the constraint
        try {
            stmt.executeUpdate() // this throws → agent captures an ERROR span
        } catch (_: SQLException) {
            // Expected – the agent still reports the span with status = ERROR
        }

        stmt.close()
        conn.close()
    }
}
