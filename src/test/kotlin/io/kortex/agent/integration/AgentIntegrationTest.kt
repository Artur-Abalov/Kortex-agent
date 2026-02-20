package io.kortex.agent.integration

import io.kortex.proto.SpanKind
import io.kortex.proto.Status
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Black-box integration tests for `kortex-agent`.
 *
 * Each test:
 * 1. Starts a [MockCollectorServer] that listens on a random port.
 * 2. Spawns a new JVM process with `-javaagent:kortex-agent.jar` pointing at
 *    the mock collector.
 * 3. The child JVM runs a small test application that performs the action
 *    under test (JDBC query, HTTP call, …).
 * 4. The test asserts on the [io.kortex.proto.Span] objects captured by the
 *    mock collector.
 *
 * The agent JAR path is injected via the `agent.jar.path` system property,
 * which the Gradle build sets automatically when it runs the `test` task.
 */
class AgentIntegrationTest {

    private lateinit var mockCollector: MockCollectorServer
    private lateinit var agentJarPath: String

    @BeforeEach
    fun setUp() {
        agentJarPath = requireNotNull(System.getProperty("agent.jar.path")) {
            "agent.jar.path system property must be set to the path of the kortex-agent fat JAR"
        }
        mockCollector = MockCollectorServer()
    }

    @AfterEach
    fun tearDown() {
        mockCollector.stop()
    }

    // ── Test Case 1: JDBC Instrumentation ───────────────────────────────────

    /**
     * Verify that a `PreparedStatement.executeQuery("SELECT 1")` triggers a
     * span with:
     * - kind = CLIENT (JDBC spans use SPAN_KIND_CLIENT per OTLP; db.system
     *   and db.statement attributes carry the database-specific context)
     * - a non-empty `trace_id` (16 bytes)
     * - `start_time < end_time`
     * - a `db.statement` attribute containing the SQL text
     */
    @Test
    fun `JDBC instrumentation sends span with CLIENT kind and SQL query`() {
        mockCollector.start(expectedSpans = 1)

        val process = spawnChildJvm(
            mainClass = "io.kortex.agent.integration.JdbcTestApp",
            programArgs = listOf("success")
        )

        assertTrue(
            mockCollector.waitForSpans(20, TimeUnit.SECONDS),
            "Timed out waiting for a JDBC span from the mock collector"
        )
        process.waitFor(5, TimeUnit.SECONDS)

        val spans = mockCollector.receivedSpans
        assertTrue(spans.isNotEmpty(), "Mock collector must have received at least one span")

        val span = spans[0]
        assertTrue(span.traceId.size() > 0, "Span must carry a non-empty trace_id")
        assertEquals(SpanKind.SPAN_KIND_CLIENT, span.kind, "Span kind must be CLIENT for database operations")
        assertTrue(
            span.startTimeUnixNano < span.endTimeUnixNano,
            "Span start time must be before end time"
        )
        assertTrue(
            span.attributesList.any { it.key == "db.statement" },
            "Span must contain a db.statement attribute with the SQL text"
        )
        assertTrue(
            span.attributesList.find { it.key == "db.statement" }
                ?.value?.stringValue?.contains("SELECT", ignoreCase = true) == true,
            "db.statement attribute must include the SELECT keyword"
        )
    }

    // ── Test Case 2: HTTP Context Propagation ────────────────────────────────

    /**
     * Verify that a W3C `traceparent` header propagates the trace context from
     * Service A to Service B so that both their JDBC spans share the same
     * `trace_id` and Span B's `parent_span_id` references Span A.
     *
     * See [HttpPropagationTestApp] for the child-JVM flow.
     */
    @Test
    fun `HTTP context propagation produces two spans with the same trace ID`() {
        mockCollector.start(expectedSpans = 2)

        val process = spawnChildJvm(
            mainClass = "io.kortex.agent.integration.HttpPropagationTestApp"
        )

        assertTrue(
            mockCollector.waitForSpans(25, TimeUnit.SECONDS),
            "Timed out waiting for 2 spans from the HTTP propagation test"
        )
        process.waitFor(5, TimeUnit.SECONDS)

        val spans = mockCollector.receivedSpans
        assertEquals(2, spans.size, "Mock collector must receive exactly 2 spans")

        val traceIds = spans.map { it.traceId }.toSet()
        assertEquals(1, traceIds.size, "Both spans must share the same trace_id")
        assertTrue(traceIds.single().size() > 0, "The shared trace_id must not be empty")

        // Span B (server-side) must reference Span A's spanId as its parent
        val spanA = spans[0]
        val spanB = spans[1]
        assertNotNull(spanB.parentSpanId.takeIf { it.size() > 0 }) {
            "Server-side span must have a non-empty parent_span_id"
        }
        assertEquals(
            spanA.spanId,
            spanB.parentSpanId,
            "Server-side span's parent_span_id must equal the client-side span's span_id"
        )
    }

    // ── Test Case 3: Error Capture ───────────────────────────────────────────

    /**
     * Verify that a JDBC error (duplicate-key violation) still produces a span
     * with:
     * - `status.code = STATUS_CODE_ERROR`
     * - an `error` attribute set to `"true"`
     * - a non-empty `error.message` attribute capturing the exception message
     */
    @Test
    fun `JDBC error is captured in a span with ERROR status and error attributes`() {
        // The error scenario produces two spans: one successful INSERT and one
        // failing INSERT.  We wait for both and assert on the error span.
        mockCollector.start(expectedSpans = 2)

        val process = spawnChildJvm(
            mainClass = "io.kortex.agent.integration.JdbcTestApp",
            programArgs = listOf("error")
        )

        assertTrue(
            mockCollector.waitForSpans(20, TimeUnit.SECONDS),
            "Timed out waiting for error spans from the mock collector"
        )
        process.waitFor(5, TimeUnit.SECONDS)

        val spans = mockCollector.receivedSpans
        assertTrue(spans.isNotEmpty(), "Mock collector must have received at least one span")

        val errorSpan = spans.find { it.status.code == Status.StatusCode.STATUS_CODE_ERROR }
        assertNotNull(errorSpan, "At least one span must have status code = STATUS_CODE_ERROR")
        assertEquals(
            "true",
            errorSpan.attributesList.find { it.key == "error" }?.value?.stringValue,
            "error attribute must be set to 'true'"
        )
        assertTrue(
            errorSpan.attributesList.find { it.key == "error.message" }
                ?.value?.stringValue?.isNotEmpty() == true,
            "error.message must capture the exception message"
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Spawn a new JVM process with the Kortex agent loaded and the full test
     * classpath, executing [mainClass] with optional [programArgs].
     *
     * stdout and stderr are merged and drained by a daemon thread to prevent
     * the child process from blocking on a full pipe buffer.
     */
    private fun spawnChildJvm(
        mainClass: String,
        programArgs: List<String> = emptyList()
    ): Process {
        val javaExe = "${System.getProperty("java.home")}/bin/java"
        val classpath = System.getProperty("java.class.path")
        val agentArg = "-javaagent:$agentJarPath=host=localhost,port=${mockCollector.port}"

        val command = mutableListOf(javaExe, agentArg, "-cp", classpath, mainClass)
        command.addAll(programArgs)

        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()

        // Drain stdout/stderr in a daemon thread to prevent the child process
        // from blocking on a full pipe buffer (the agent produces verbose output).
        Thread({
            process.inputStream.use { it.copyTo(OutputStream.nullOutputStream()) }
        }, "child-output-drainer").apply {
            isDaemon = true
            start()
        }

        return process
    }
}
