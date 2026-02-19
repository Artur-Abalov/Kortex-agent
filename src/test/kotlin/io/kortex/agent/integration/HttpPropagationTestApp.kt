package io.kortex.agent.integration

import com.sun.net.httpserver.HttpServer
import io.kortex.agent.ContextManager
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch

/**
 * Test application that verifies W3C trace-context propagation across a
 * simulated service boundary executed within a single child JVM process.
 *
 * Flow
 * ----
 * 1. **Service A** (main thread) establishes a trace context by executing a
 *    JDBC `SELECT 1` query → the Kortex agent creates Span A with a new
 *    `trace_id`.
 * 2. Service A manually reads the current `traceparent` value from
 *    [ContextManager] and injects it as an HTTP request header before calling
 *    Service B.
 * 3. **Service B** (HTTP-handler thread) reads the `traceparent` header and
 *    calls [ContextManager.parseTraceparent] to adopt the same `trace_id` and
 *    `parent_span_id` on its own thread.
 * 4. Service B executes a JDBC `SELECT 2` query → the Kortex agent creates
 *    Span B **with the same `trace_id`** and `parent_span_id = Span A's spanId`.
 *
 * The mock collector (running in the parent test process) should therefore
 * receive exactly **two** spans that share a `trace_id`.
 */
object HttpPropagationTestApp {

    @JvmStatic
    fun main(args: Array<String>) {
        // Give the agent a moment to finish its own initialisation
        Thread.sleep(500)

        val connA = DriverManager.getConnection("jdbc:h2:mem:prop_a;DB_CLOSE_DELAY=-1")
        val connB = DriverManager.getConnection("jdbc:h2:mem:prop_b;DB_CLOSE_DELAY=-1")

        val serverDone = CountDownLatch(1)

        // ── Service B: lightweight HTTP server ──────────────────────────────
        val httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.createContext("/") { exchange ->
            try {
                // Adopt the trace context propagated by Service A
                val traceparent = exchange.requestHeaders.getFirst("traceparent")
                if (traceparent != null) {
                    ContextManager.parseTraceparent(traceparent)
                }

                // Service B's JDBC call – agent records Span B (same trace_id)
                val stmt = connB.prepareStatement("SELECT 2")
                stmt.executeQuery()
                stmt.close()

                val body = "OK".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                serverDone.countDown()
            }
        }
        httpServer.start()

        // ── Service A: establish trace context, then call Service B ─────────
        // This JDBC call causes the agent to generate a new trace_id for this
        // thread and records Span A.
        val stmtA = connA.prepareStatement("SELECT 1")
        stmtA.executeQuery()
        stmtA.close()

        // Read the trace context that the agent just established and build the
        // W3C traceparent header to carry it to Service B.
        val traceparent = ContextManager.generateTraceparent()

        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:${httpServer.address.port}/"))
            .header("traceparent", traceparent)
            .GET()
            .build()
        client.send(request, HttpResponse.BodyHandlers.ofString())

        // Wait for Service B to finish before shutting down
        serverDone.await()
        httpServer.stop(0)

        connA.close()
        connB.close()

        // Wait long enough for the agent's async reporter to flush both spans
        Thread.sleep(3000)
    }
}
