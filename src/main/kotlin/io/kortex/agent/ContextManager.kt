package io.kortex.agent

import java.util.UUID

/**
 * ContextManager manages trace context using ThreadLocal storage.
 * Implements W3C Trace Context specification for distributed tracing.
 */
object ContextManager {
    private val traceIdHolder = ThreadLocal<String>()
    private val spanIdHolder = ThreadLocal<String>()
    private val parentSpanIdHolder = ThreadLocal<String?>()
    
    /**
     * Get or create a trace ID for the current thread.
     * If no trace ID exists, generates a new UUID.
     */
    fun getOrCreateTraceId(): String {
        var traceId = traceIdHolder.get()
        if (traceId == null) {
            traceId = generateTraceId()
            traceIdHolder.set(traceId)
        }
        return traceId
    }
    
    /**
     * Get the current trace ID without creating a new one.
     */
    fun getTraceId(): String? = traceIdHolder.get()
    
    /**
     * Set the trace ID for the current thread.
     */
    fun setTraceId(traceId: String) {
        traceIdHolder.set(traceId)
    }
    
    /**
     * Generate a new span ID.
     */
    fun generateSpanId(): String {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    }
    
    /**
     * Get the current span ID.
     */
    fun getSpanId(): String? = spanIdHolder.get()
    
    /**
     * Set the span ID for the current thread.
     */
    fun setSpanId(spanId: String) {
        spanIdHolder.set(spanId)
    }
    
    /**
     * Get the parent span ID.
     */
    fun getParentSpanId(): String? = parentSpanIdHolder.get()
    
    /**
     * Set the parent span ID for the current thread.
     */
    fun setParentSpanId(parentSpanId: String?) {
        parentSpanIdHolder.set(parentSpanId)
    }
    
    /**
     * Clear all context for the current thread.
     */
    fun clear() {
        traceIdHolder.remove()
        spanIdHolder.remove()
        parentSpanIdHolder.remove()
    }
    
    /**
     * Generate a new trace ID (32 hex characters for W3C compliance).
     */
    private fun generateTraceId(): String {
        return UUID.randomUUID().toString().replace("-", "")
    }
    
    /**
     * Parse W3C traceparent header.
     * Format: 00-{trace-id}-{parent-id}-{trace-flags}
     * Example: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
     */
    fun parseTraceparent(traceparent: String?): Boolean {
        if (traceparent == null) return false
        
        val parts = traceparent.split("-")
        if (parts.size != 4) return false
        
        val version = parts[0]
        val traceId = parts[1]
        val parentId = parts[2]
        
        if (version != "00") return false
        if (traceId.length != 32) return false
        if (parentId.length != 16) return false
        
        setTraceId(traceId)
        setParentSpanId(parentId)
        
        return true
    }
    
    /**
     * Generate W3C traceparent header.
     * Format: 00-{trace-id}-{parent-id}-{trace-flags}
     */
    fun generateTraceparent(): String {
        val traceId = getOrCreateTraceId()
        val spanId = getSpanId() ?: generateSpanId()
        return "00-$traceId-$spanId-01"
    }

    /**
     * Convert a lowercase hex string to a byte array.
     * Used to convert 32-char trace IDs (16 bytes) and 16-char span IDs (8 bytes)
     * to the byte representation required by the OTLP proto schema.
     *
     * Only call this with hex strings produced by [generateTraceId] and [generateSpanId];
     * the format is always valid and even-length in normal operation.
     */
    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Invalid hex string: length must be even, got ${hex.length}" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
