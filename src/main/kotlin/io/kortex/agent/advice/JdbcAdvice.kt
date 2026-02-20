package io.kortex.agent.advice

import com.google.protobuf.ByteString
import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.agent.sanitization.SqlSanitizer
import io.kortex.proto.AnyValue
import io.kortex.proto.KeyValue
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
import io.kortex.proto.Status
import net.bytebuddy.asm.Advice

/**
 * Advice for JDBC PreparedStatement instrumentation.
 * Intercepts execute methods to capture SQL queries and timings.
 *
 * ThreadLocal stacks are used instead of @Advice.Local to avoid null-initialization
 * issues: @Advice.Local object-type locals start as null, making list operations
 * throw NPE before ByteBuddy can propagate any value.
 */
class JdbcAdvice {

    companion object {
        private val startTimeStack = ThreadLocal.withInitial { ArrayDeque<Long>() }
        private val spanIdStack = ThreadLocal.withInitial { ArrayDeque<String>() }

        /**
         * Entry advice - captures start time and generates a new span ID.
         */
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter() {
            try {
                startTimeStack.get().addLast(System.nanoTime())

                val currentSpanId = ContextManager.generateSpanId()
                spanIdStack.get().addLast(currentSpanId)

                // Store current span as parent for nested operations
                val previousSpanId = ContextManager.getSpanId()
                if (previousSpanId != null) {
                    ContextManager.setParentSpanId(previousSpanId)
                }
                ContextManager.setSpanId(currentSpanId)
            } catch (e: Throwable) {
                // Suppress errors to avoid crashing host application
            }
        }

        /**
         * Exit advice - calculates duration and creates span.
         */
        @JvmStatic
        @Advice.OnMethodExit(onThrowable = Throwable::class)
        fun onExit(
            @Advice.This statement: Any,
            @Advice.Origin("#m") methodName: String,
            @Advice.Thrown thrown: Throwable?
        ) {
            try {
                val startDeque = startTimeStack.get()
                val spanDeque = spanIdStack.get()
                if (startDeque.isEmpty() || spanDeque.isEmpty()) return

                val startTime = startDeque.removeLast()
                val currentSpanId = spanDeque.removeLast()
                val endTime = System.nanoTime()

                val traceId = ContextManager.getOrCreateTraceId()
                val parentSpanId = ContextManager.getParentSpanId()

                // Try to extract SQL query from PreparedStatement
                val sqlQuery = extractSqlQuery(statement)

                val kvAttributes = mutableListOf<KeyValue>()
                kvAttributes.add(buildStringKv("db.system", "jdbc"))
                kvAttributes.add(buildStringKv("db.operation", methodName))
                if (sqlQuery != null) {
                    kvAttributes.add(buildStringKv("db.statement", SqlSanitizer.sanitize(sqlQuery) ?: sqlQuery))
                }

                val status = if (thrown != null) {
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
                    .setName("jdbc.$methodName")
                    .setKind(SpanKind.SPAN_KIND_CLIENT)
                    .setStartTimeUnixNano(startTime)
                    .setEndTimeUnixNano(endTime)
                    .addAllAttributes(kvAttributes)
                    .setStatus(status)
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

        /**
         * Attempt to extract SQL query from PreparedStatement.
         * Tries common reflection-based field names first, then falls back to toString().
         */
        private fun extractSqlQuery(statement: Any): String? {
            for (fieldName in listOf("sql", "originalSql", "commandText")) {
                try {
                    var clazz: Class<*>? = statement.javaClass
                    while (clazz != null) {
                        try {
                            val field = clazz.getDeclaredField(fieldName)
                            field.isAccessible = true
                            val value = field.get(statement)?.toString()
                            if (!value.isNullOrEmpty()) return value
                        } catch (_: NoSuchFieldException) {
                            // field not in this class, check superclass
                        }
                        clazz = clazz.superclass
                    }
                } catch (_: Exception) {
                    // Try next field name
                }
            }
            // Fall back to toString() – many JDBC drivers include the SQL there
            return try {
                statement.toString().takeIf { it.isNotEmpty() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
