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
 * Advice for HTTP Client (outgoing requests) instrumentation.
 * Intercepts outgoing HTTP requests to inject trace context.
 *
 * ThreadLocal stacks are used instead of @Advice.Local to avoid null-initialization
 * issues: @Advice.Local object-type locals start as null, making list operations
 * throw NPE before ByteBuddy can propagate any value.
 */
class HttpClientAdvice {

    companion object {
        private val startTimeStack = ThreadLocal.withInitial { ArrayDeque<Long>() }
        private val spanIdStack = ThreadLocal.withInitial { ArrayDeque<String>() }

        /**
         * Entry advice - injects traceparent header into outgoing request.
         */
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(
            @Advice.Argument(0) request: Any?
        ) {
            try {
                startTimeStack.get().addLast(System.nanoTime())

                val currentSpanId = ContextManager.generateSpanId()
                spanIdStack.get().addLast(currentSpanId)

                // Store current span as parent
                val previousSpanId = ContextManager.getSpanId()
                if (previousSpanId != null) {
                    ContextManager.setParentSpanId(previousSpanId)
                }
                ContextManager.setSpanId(currentSpanId)

                // Inject traceparent header into the request
                if (request != null) {
                    try {
                        val traceparent = ContextManager.generateTraceparent()
                        val tracestate = ContextManager.getTraceState()

                        // Try to inject header (method varies by HTTP client implementation)
                        try {
                            val setHeaderMethod = request.javaClass.getMethod("setHeader", String::class.java, String::class.java)
                            setHeaderMethod.invoke(request, "traceparent", traceparent)
                            if (!tracestate.isNullOrEmpty()) {
                                setHeaderMethod.invoke(request, "tracestate", tracestate)
                            }
                        } catch (e: Exception) {
                            // Try alternate method for header injection
                            try {
                                val headerMethod = request.javaClass.getMethod("header", String::class.java, String::class.java)
                                headerMethod.invoke(request, "traceparent", traceparent)
                                if (!tracestate.isNullOrEmpty()) {
                                    headerMethod.invoke(request, "tracestate", tracestate)
                                }
                            } catch (_: Exception) {
                                // Silently fail if we can't inject header
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore injection failures
                    }
                }
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            }
        }

        /**
         * Exit advice - creates span for the HTTP client request.
         */
        @JvmStatic
        @Advice.OnMethodExit(onThrowable = Throwable::class)
        fun onExit(
            @Advice.Argument(0) request: Any?,
            @Advice.Return returnValue: Any?,
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

                if (request != null) {
                    try {
                        val uriMethod = request.javaClass.getMethod("uri")
                        val uri = uriMethod.invoke(request)
                        if (uri != null) {
                            kvAttributes.add(buildStringKv("http.url", uri.toString()))
                        }

                        val methodMethod = request.javaClass.getMethod("method")
                        val method = methodMethod.invoke(request)
                        if (method != null) {
                            kvAttributes.add(buildStringKv("http.method", method.toString()))
                        }
                    } catch (_: Exception) {
                        // Ignore if we can't extract details
                    }

                    // Capture headers in a sanitized manner
                    try {
                        val headersMethod = request.javaClass.getMethod("headers")
                        val headers = headersMethod.invoke(request)
                        if (headers != null) {
                            val mapMethod = headers.javaClass.getMethod("map")
                            @Suppress("UNCHECKED_CAST")
                            val headerMap = mapMethod.invoke(headers) as? Map<String, List<String>>
                            headerMap?.forEach { (name, values) ->
                                val sanitizedValues = values.map { HeaderSanitizer.sanitize(name, it) }
                                kvAttributes.add(buildStringKv("http.request.header.$name", sanitizedValues.joinToString(", ")))
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore if we can't extract headers
                    }
                }

                // Extract response details
                if (returnValue != null && thrown == null) {
                    try {
                        val statusCodeMethod = returnValue.javaClass.getMethod("statusCode")
                        val statusCode = statusCodeMethod.invoke(returnValue) as? Int
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
                val httpUrl = kvAttributes.find { it.key == "http.url" }?.value?.stringValue ?: "?"

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
                    .setName("HTTP $httpMethod $httpUrl")
                    .setKind(SpanKind.SPAN_KIND_CLIENT)
                    .setStartTimeUnixNano(startTime)
                    .setEndTimeUnixNano(endTime)
                    .addAllAttributes(kvAttributes)
                    .setStatus(spanStatus)
                    .setFlags(ContextManager.getTraceFlags())
                    .build()

                SpanReporter.reportSpan(span)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            }
        }

        private fun buildStringKv(key: String, value: String): KeyValue =
            KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value).build())
                .build()
    }
}
