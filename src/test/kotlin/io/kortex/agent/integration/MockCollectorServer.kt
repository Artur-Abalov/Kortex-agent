package io.kortex.agent.integration

import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.stub.StreamObserver
import io.kortex.proto.ExportTraceServiceRequest
import io.kortex.proto.ExportTraceServiceResponse
import io.kortex.proto.Span
import io.kortex.proto.TraceServiceGrpc
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A lightweight in-process gRPC server that impersonates Kortex Core.
 *
 * It collects all [Span] objects received via [TraceService.Export] into
 * [receivedSpans] and uses a [CountDownLatch] so tests can block until an
 * expected number of spans arrive.
 *
 * Spans are extracted from the OTLP hierarchy:
 * ExportTraceServiceRequest → ResourceSpans → ScopeSpans → Span
 *
 * Usage:
 * ```
 * val collector = MockCollectorServer()
 * collector.start(expectedSpans = 1)
 * // …spawn child JVM…
 * assertTrue(collector.waitForSpans(20, TimeUnit.SECONDS))
 * collector.stop()
 * ```
 */
class MockCollectorServer {

    private var server: Server? = null

    /** All spans received since the last [start] call. Thread-safe. */
    val receivedSpans: MutableList<Span> = CopyOnWriteArrayList()

    private var latch: CountDownLatch = CountDownLatch(1)

    /** The port the server is actually listening on (set after [start]). */
    var port: Int = 0
        private set

    /**
     * Start the mock collector and prepare to collect [expectedSpans] spans.
     * The [waitForSpans] latch is reset on every call to [start].
     */
    fun start(expectedSpans: Int = 1) {
        receivedSpans.clear()
        latch = CountDownLatch(expectedSpans)

        val service = object : TraceServiceGrpc.TraceServiceImplBase() {
            override fun export(
                request: ExportTraceServiceRequest,
                responseObserver: StreamObserver<ExportTraceServiceResponse>
            ) {
                request.resourceSpansList.forEach { resourceSpans ->
                    resourceSpans.scopeSpansList.forEach { scopeSpans ->
                        scopeSpans.spansList.forEach { span ->
                            receivedSpans.add(span)
                            latch.countDown()
                        }
                    }
                }
                responseObserver.onNext(ExportTraceServiceResponse.newBuilder().build())
                responseObserver.onCompleted()
            }
        }

        server = ServerBuilder.forPort(0)
            .addService(service)
            .build()
            .start()

        port = server!!.port
    }

    /**
     * Block until the expected number of spans arrive or [timeout] elapses.
     * @return `true` if all expected spans were received in time.
     */
    fun waitForSpans(timeout: Long = 15, unit: TimeUnit = TimeUnit.SECONDS): Boolean =
        latch.await(timeout, unit)

    /** Shut down the gRPC server gracefully. */
    fun stop() {
        server?.shutdown()
        server?.awaitTermination(5, TimeUnit.SECONDS)
    }
}
