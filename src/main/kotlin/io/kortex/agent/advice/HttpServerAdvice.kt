package io.kortex.agent.advice

import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
import net.bytebuddy.asm.Advice

/**
 * Advice for HTTP Servlet (server-side) instrumentation.
 * Intercepts incoming HTTP requests to extract trace context.
 */
class HttpServerAdvice {
    
    companion object {
        /**
         * Entry advice - extracts traceparent header and sets up context.
         */
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(
            @Advice.Argument(0) request: Any?,
            @Advice.Argument(1) response: Any?,
            @Advice.Local("startTime") startTime: MutableList<Long>,
            @Advice.Local("spanId") spanId: MutableList<String>
        ) {
            try {
                startTime.add(System.nanoTime())
                
                // Extract traceparent header if present
                if (request != null) {
                    try {
                        val getHeaderMethod = request.javaClass.getMethod("getHeader", String::class.java)
                        val traceparent = getHeaderMethod.invoke(request, "traceparent") as? String
                        
                        if (traceparent != null && ContextManager.parseTraceparent(traceparent)) {
                            // Trace context extracted from header
                        } else {
                            // Create new trace context
                            ContextManager.getOrCreateTraceId()
                        }
                    } catch (e: Exception) {
                        // If we can't extract header, create new context
                        ContextManager.getOrCreateTraceId()
                    }
                }
                
                val currentSpanId = ContextManager.generateSpanId()
                spanId.add(currentSpanId)
                ContextManager.setSpanId(currentSpanId)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            }
        }
        
        /**
         * Exit advice - creates span for the HTTP request.
         */
        @JvmStatic
        @Advice.OnMethodExit(onThrowable = Throwable::class)
        fun onExit(
            @Advice.Argument(0) request: Any?,
            @Advice.Argument(1) response: Any?,
            @Advice.Local("startTime") startTime: List<Long>,
            @Advice.Local("spanId") spanId: List<String>,
            @Advice.Thrown thrown: Throwable?
        ) {
            try {
                if (startTime.isEmpty() || spanId.isEmpty()) return
                
                val endTime = System.nanoTime()
                
                val traceId = ContextManager.getTraceId() ?: return
                val currentSpanId = spanId[0]
                val parentSpanId = ContextManager.getParentSpanId()
                
                // Extract request details
                val attributes = mutableMapOf<String, String>()
                attributes["http.flavor"] = "HTTP/1.1"
                
                if (request != null) {
                    try {
                        val getMethodMethod = request.javaClass.getMethod("getMethod")
                        val method = getMethodMethod.invoke(request) as? String
                        if (method != null) attributes["http.method"] = method
                        
                        val getRequestURIMethod = request.javaClass.getMethod("getRequestURI")
                        val uri = getRequestURIMethod.invoke(request) as? String
                        if (uri != null) attributes["http.target"] = uri
                        
                        val getQueryStringMethod = request.javaClass.getMethod("getQueryString")
                        val queryString = getQueryStringMethod.invoke(request) as? String
                        if (queryString != null) attributes["http.query"] = queryString
                    } catch (e: Exception) {
                        // Ignore if we can't extract details
                    }
                }
                
                if (response != null) {
                    try {
                        val getStatusMethod = response.javaClass.getMethod("getStatus")
                        val status = getStatusMethod.invoke(response) as? Int
                        if (status != null) attributes["http.status_code"] = status.toString()
                    } catch (e: Exception) {
                        // Ignore if we can't extract status
                    }
                }
                
                val status = if (thrown != null) {
                    attributes["error"] = "true"
                    attributes["error.type"] = thrown.javaClass.name
                    attributes["error.message"] = thrown.message ?: ""
                    "ERROR"
                } else {
                    "OK"
                }
                
                val span = Span.newBuilder()
                    .setTraceId(traceId)
                    .setSpanId(currentSpanId)
                    .apply {
                        if (parentSpanId != null) {
                            setParentSpanId(parentSpanId)
                        }
                    }
                    .setName("HTTP ${attributes["http.method"] ?: "?"} ${attributes["http.target"] ?: "?"}")
                    .setKind(SpanKind.SPAN_KIND_SERVER)
                    .setStartTimeUnixNano(startTime[0])
                    .setEndTimeUnixNano(endTime)
                    .putAllAttributes(attributes)
                    .setStatus(status)
                    .build()
                
                SpanReporter.reportSpan(span)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            } finally {
                // Clean up context after request processing
                ContextManager.clear()
            }
        }
    }
}
