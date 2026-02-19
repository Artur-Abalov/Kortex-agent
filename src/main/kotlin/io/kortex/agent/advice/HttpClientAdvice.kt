package io.kortex.agent.advice

import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
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

                        // Try to inject header (method varies by HTTP client implementation)
                        try {
                            val setHeaderMethod = request.javaClass.getMethod("setHeader", String::class.java, String::class.java)
                            setHeaderMethod.invoke(request, "traceparent", traceparent)
                        } catch (e: Exception) {
                            // Try alternate method for header injection
                            try {
                                val headerMethod = request.javaClass.getMethod("header", String::class.java, String::class.java)
                                headerMethod.invoke(request, "traceparent", traceparent)
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
                val attributes = mutableMapOf<String, String>()

                if (request != null) {
                    try {
                        val uriMethod = request.javaClass.getMethod("uri")
                        val uri = uriMethod.invoke(request)
                        if (uri != null) {
                            attributes["http.url"] = uri.toString()
                        }

                        val methodMethod = request.javaClass.getMethod("method")
                        val method = methodMethod.invoke(request)
                        if (method != null) {
                            attributes["http.method"] = method.toString()
                        }
                    } catch (_: Exception) {
                        // Ignore if we can't extract details
                    }
                }

                // Extract response details
                if (returnValue != null && thrown == null) {
                    try {
                        val statusCodeMethod = returnValue.javaClass.getMethod("statusCode")
                        val statusCode = statusCodeMethod.invoke(returnValue) as? Int
                        if (statusCode != null) {
                            attributes["http.status_code"] = statusCode.toString()
                        }
                    } catch (_: Exception) {
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
                    .setName("HTTP ${attributes["http.method"] ?: "?"} ${attributes["http.url"] ?: "?"}")
                    .setKind(SpanKind.SPAN_KIND_CLIENT)
                    .setStartTimeUnixNano(startTime)
                    .setEndTimeUnixNano(endTime)
                    .putAllAttributes(attributes)
                    .setStatus(status)
                    .build()

                SpanReporter.reportSpan(span)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            }
        }
    }
}
