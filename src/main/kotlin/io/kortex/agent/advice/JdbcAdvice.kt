package io.kortex.agent.advice

import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
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

                val attributes = mutableMapOf<String, String>()
                attributes["db.system"] = "jdbc"
                attributes["db.operation"] = methodName
                if (sqlQuery != null) {
                    attributes["db.statement"] = sqlQuery
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
                    .setName("jdbc.$methodName")
                    .setKind(SpanKind.SPAN_KIND_DB)
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
