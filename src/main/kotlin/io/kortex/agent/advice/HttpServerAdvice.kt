package io.kortex.agent.advice

import com.google.protobuf.ByteString
import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.agent.sanitization.HeaderSanitizer
import io.kortex.proto.AnyValue
import io.kortex.proto.KeyValue
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
import io.kortex.proto.Status
import net.bytebuddy.asm.Advice

/**
 * Advice for HTTP Servlet (server-side) instrumentation.
 * Intercepts incoming HTTP requests to extract trace context.
 *
 * ThreadLocal stacks are used instead of @Advice.Local to avoid null-initialization
 * issues: @Advice.Local object-type locals start as null, making list operations
 * throw NPE before ByteBuddy can propagate any value.
 */
class HttpServerAdvice {

    companion object {
        private val startTimeStack = ThreadLocal.withInitial { ArrayDeque<Long>() }
        private val spanIdStack = ThreadLocal.withInitial { ArrayDeque<String>() }

        /**
         * Entry advice - extracts traceparent header and sets up context.
         */
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(
            @Advice.Argument(0) request: Any?,
            @Advice.Argument(1) response: Any?
        ) {
            try {
                startTimeStack.get().addLast(System.nanoTime())

                // Extract traceparent header if present
                if (request != null) {
                    try {
                        val getHeaderMethod = request.javaClass.getMethod("getHeader", String::class.java)
                        val traceparent = getHeaderMethod.invoke(request, "traceparent") as? String

                        if (traceparent == null || !ContextManager.parseTraceparent(traceparent)) {
                            // Create new trace context if no valid header found
                            ContextManager.getOrCreateTraceId()
                        }

                        // Capture tracestate for end-to-end propagation
                        val tracestate = getHeaderMethod.invoke(request, "tracestate") as? String
                        if (!tracestate.isNullOrEmpty()) {
                            ContextManager.setTraceState(tracestate)
                        }
                    } catch (_: Exception) {
                        // If we can't extract header, create new context
                        ContextManager.getOrCreateTraceId()
                    }
                }

                val currentSpanId = ContextManager.generateSpanId()
                spanIdStack.get().addLast(currentSpanId)
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
            @Advice.Thrown thrown: Throwable?
        ) {
            try {
                val startDeque = startTimeStack.get()
                val spanDeque = spanIdStack.get()
                if (startDeque.isEmpty() || spanDeque.isEmpty()) return

                val startTime = startDeque.removeLast()
                val currentSpanId = spanDeque.removeLast()
                val endTime = System.nanoTime()

                val traceId = ContextManager.getTraceId() ?: return
                val parentSpanId = ContextManager.getParentSpanId()

                // Extract request details
                val kvAttributes = mutableListOf<KeyValue>()
                kvAttributes.add(buildStringKv("http.flavor", "HTTP/1.1"))

                if (request != null) {
                    try {
                        val getMethodMethod = request.javaClass.getMethod("getMethod")
                        val method = getMethodMethod.invoke(request) as? String
                        if (method != null) kvAttributes.add(buildStringKv("http.method", method))

                        val getRequestURIMethod = request.javaClass.getMethod("getRequestURI")
                        val uri = getRequestURIMethod.invoke(request) as? String
                        if (uri != null) kvAttributes.add(buildStringKv("http.target", uri))

                        val getQueryStringMethod = request.javaClass.getMethod("getQueryString")
                        val queryString = getQueryStringMethod.invoke(request) as? String
                        if (queryString != null) kvAttributes.add(buildStringKv("http.query", queryString))

                        // Capture headers in a sanitized manner
                        try {
                            val getHeaderNamesMethod = request.javaClass.getMethod("getHeaderNames")
                            val headerNames = getHeaderNamesMethod.invoke(request)
                            if (headerNames is java.util.Enumeration<*>) {
                                val getHdr = request.javaClass.getMethod("getHeader", String::class.java)
                                for (name in headerNames) {
                                    val headerName = name as? String ?: continue
                                    val headerValue = getHdr.invoke(request, headerName) as? String ?: continue
                                    kvAttributes.add(
                                        buildStringKv(
                                            "http.request.header.$headerName",
                                            HeaderSanitizer.sanitize(headerName, headerValue)
                                        )
                                    )
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore if we can't enumerate headers
                        }
                    } catch (_: Exception) {
                        // Ignore if we can't extract details
                    }
                }

                if (response != null) {
                    try {
                        val getStatusMethod = response.javaClass.getMethod("getStatus")
                        val statusCode = getStatusMethod.invoke(response) as? Int
                        if (statusCode != null) {
                            kvAttributes.add(buildStringKv("http.status_code", statusCode.toString()))
                        }
                    } catch (_: Exception) {
                        // Ignore if we can't extract status
                    }
                }

                val spanStatus = if (thrown != null) {
                    kvAttributes.add(buildStringKv("error", "true"))
                    kvAttributes.add(buildStringKv("error.type", thrown.javaClass.name))
                    kvAttributes.add(buildStringKv("error.message", thrown.message ?: ""))
                    Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                        .setMessage(thrown.message ?: "")
                        .build()
                } else {
                    Status.newBuilder()
                        .setCode(Status.StatusCode.STATUS_CODE_OK)
                        .build()
                }

                val httpMethod = kvAttributes.find { it.key == "http.method" }?.value?.stringValue ?: "?"
                val httpTarget = kvAttributes.find { it.key == "http.target" }?.value?.stringValue ?: "?"

                val span = Span.newBuilder()
                    .setTraceId(ByteString.copyFrom(ContextManager.hexToBytes(traceId)))
                    .setSpanId(ByteString.copyFrom(ContextManager.hexToBytes(currentSpanId)))
                    .apply {
                        if (parentSpanId != null) {
                            setParentSpanId(ByteString.copyFrom(ContextManager.hexToBytes(parentSpanId)))
                        }
                        val traceState = ContextManager.getTraceState()
                        if (!traceState.isNullOrEmpty()) {
                            setTraceState(traceState)
                        }
                    }
                    .setName("HTTP $httpMethod $httpTarget")
                    .setKind(SpanKind.SPAN_KIND_SERVER)
                    .setStartTimeUnixNano(startTime)
                    .setEndTimeUnixNano(endTime)
                    .addAllAttributes(kvAttributes)
                    .setStatus(spanStatus)
                    .setFlags(ContextManager.getTraceFlags())
                    .build()

                SpanReporter.reportSpan(span)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            } finally {
                // Clean up context after request processing
                ContextManager.clear()
            }
        }

        private fun buildStringKv(key: String, value: String): KeyValue =
            KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build()
    }
}
