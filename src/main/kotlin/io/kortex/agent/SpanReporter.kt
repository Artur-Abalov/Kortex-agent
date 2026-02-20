package io.kortex.agent

import io.kortex.proto.ExportTraceServiceRequest
import io.kortex.proto.InstrumentationScope
import io.kortex.proto.Resource
import io.kortex.proto.ResourceSpans
import io.kortex.proto.ScopeSpans
import io.kortex.proto.Span
import io.kortex.proto.TraceServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * SpanReporter manages async reporting of spans to Kortex Core.
 * Uses a background daemon thread to batch and send spans via gRPC.
 *
 * Spans are sent using the OTLP hierarchy:
 * ExportTraceServiceRequest → ResourceSpans → ScopeSpans → Span
 */
object SpanReporter {
    private val spanQueue = LinkedBlockingQueue<Span>(10000)
    private var channel: ManagedChannel? = null
    private var stub: TraceServiceGrpc.TraceServiceBlockingStub? = null
    private var reporterThread: Thread? = null
    private var running = false
    
    private const val BATCH_SIZE = 100
    private const val BATCH_TIMEOUT_MS = 1000L

    // InstrumentationScope identifying this agent as the telemetry producer
    private val agentScope: InstrumentationScope = InstrumentationScope.newBuilder()
        .setName("io.kortex.agent")
        .setVersion("1.0.0")
        .build()

    // Resource representing the monitored service (no attributes by default)
    private val serviceResource: Resource = Resource.newBuilder().build()
    
    /**
     * Initialize the reporter with gRPC endpoint.
     */
    fun initialize(host: String = "localhost", port: Int = 9090) {
        if (running) return
        
        try {
            channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build()
            
            stub = TraceServiceGrpc.newBlockingStub(channel)
            
            running = true
            reporterThread = Thread(ReporterTask(), "KortexSpanReporter").apply {
                isDaemon = true
                start()
            }
            
            println("[Kortex] SpanReporter initialized with endpoint $host:$port")
        } catch (e: Exception) {
            System.err.println("[Kortex] Failed to initialize SpanReporter: ${e.message}")
        }
    }
    
    /**
     * Submit a span to the reporter queue.
     */
    fun reportSpan(span: Span) {
        if (!running) {
            // Silently drop if not initialized to avoid crashing the host app
            return
        }
        
        try {
            if (!spanQueue.offer(span)) {
                System.err.println("[Kortex] Span queue full, dropping span")
            }
        } catch (e: Exception) {
            System.err.println("[Kortex] Error reporting span: ${e.message}")
        }
    }
    
    /**
     * Shutdown the reporter gracefully.
     */
    fun shutdown() {
        running = false
        reporterThread?.interrupt()
        
        channel?.shutdown()
        try {
            channel?.awaitTermination(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            channel?.shutdownNow()
        }
    }
    
    /**
     * Background task that polls the queue and sends batches.
     */
    private class ReporterTask : Runnable {
        override fun run() {
            val batch = mutableListOf<Span>()
            var lastSendTime = System.currentTimeMillis()
            
            while (running) {
                try {
                    // Poll with timeout
                    val span = spanQueue.poll(100, TimeUnit.MILLISECONDS)
                    
                    if (span != null) {
                        batch.add(span)
                    }
                    
                    val now = System.currentTimeMillis()
                    val shouldSend = batch.size >= BATCH_SIZE || 
                                   (batch.isNotEmpty() && (now - lastSendTime) >= BATCH_TIMEOUT_MS)
                    
                    if (shouldSend) {
                        sendBatch(batch)
                        batch.clear()
                        lastSendTime = now
                    }
                } catch (e: InterruptedException) {
                    // Thread interrupted, exit gracefully
                    break
                } catch (e: Exception) {
                    System.err.println("[Kortex] Error in reporter thread: ${e.message}")
                }
            }
            
            // Send remaining spans before exit
            if (batch.isNotEmpty()) {
                try {
                    sendBatch(batch)
                } catch (e: Exception) {
                    System.err.println("[Kortex] Error sending final batch: ${e.message}")
                }
            }
        }
        
        private fun sendBatch(spans: List<Span>) {
            if (spans.isEmpty()) return
            
            try {
                val scopeSpans = ScopeSpans.newBuilder()
                    .setScope(agentScope)
                    .addAllSpans(spans)
                    .build()

                val resourceSpans = ResourceSpans.newBuilder()
                    .setResource(serviceResource)
                    .addScopeSpans(scopeSpans)
                    .build()

                val request = ExportTraceServiceRequest.newBuilder()
                    .addResourceSpans(resourceSpans)
                    .build()
                
                val response = stub?.export(request)

                val rejected = response?.partialSuccess?.rejectedSpans ?: 0L
                if (rejected == 0L) {
                    println("[Kortex] Successfully sent batch of ${spans.size} spans")
                } else {
                    System.err.println(
                        "[Kortex] Partial failure: $rejected spans rejected — " +
                            "${response?.partialSuccess?.errorMessage}"
                    )
                }
            } catch (e: Exception) {
                System.err.println("[Kortex] Error sending batch to Kortex Core: ${e.message}")
            }
        }
    }
}
