package io.kortex.agent.advice

import io.kortex.agent.ContextManager
import io.kortex.agent.SpanReporter
import io.kortex.proto.Span
import io.kortex.proto.SpanKind
import net.bytebuddy.asm.Advice

/**
 * Advice for JDBC PreparedStatement instrumentation.
 * Intercepts execute methods to capture SQL queries and timings.
 */
class JdbcAdvice {
    
    companion object {
        /**
         * Entry advice - captures start time and SQL query.
         */
        @JvmStatic
        @Advice.OnMethodEnter
        fun onEnter(
            @Advice.This statement: Any,
            @Advice.Origin("#m") methodName: String,
            @Advice.Local("startTime") startTime: MutableList<Long>,
            @Advice.Local("spanId") spanId: MutableList<String>
        ) {
            try {
                startTime.add(System.nanoTime())
                
                val currentSpanId = ContextManager.generateSpanId()
                spanId.add(currentSpanId)
                
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
            @Advice.Local("startTime") startTime: List<Long>,
            @Advice.Local("spanId") spanId: List<String>,
            @Advice.Thrown thrown: Throwable?
        ) {
            try {
                if (startTime.isEmpty() || spanId.isEmpty()) return
                
                val endTime = System.nanoTime()
                val duration = endTime - startTime[0]
                
                val traceId = ContextManager.getOrCreateTraceId()
                val currentSpanId = spanId[0]
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
                    .setName("jdbc.${methodName}")
                    .setKind(SpanKind.SPAN_KIND_DB)
                    .setStartTimeUnixNano(startTime[0])
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
         * This is implementation-specific and may not work for all JDBC drivers.
         */
        private fun extractSqlQuery(statement: Any): String? {
            try {
                // Try to access the SQL string through reflection
                val sqlField = statement.javaClass.getDeclaredField("sql")
                sqlField.isAccessible = true
                return sqlField.get(statement)?.toString()
            } catch (e: Exception) {
                // Try alternate field names used by different JDBC drivers
                try {
                    val originalSqlField = statement.javaClass.getDeclaredField("originalSql")
                    originalSqlField.isAccessible = true
                    return originalSqlField.get(statement)?.toString()
                } catch (e2: Exception) {
                    // If we can't extract the SQL, use toString which often contains it
                    try {
                        val str = statement.toString()
                        if (str.contains("sql=")) {
                            return str.substringAfter("sql=").substringBefore(",").trim()
                        }
                    } catch (e3: Exception) {
                        // Ignore
                    }
                }
            }
            return null
        }
    }
}
