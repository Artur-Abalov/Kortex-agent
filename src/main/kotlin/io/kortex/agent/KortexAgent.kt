package io.kortex.agent

import io.kortex.agent.internal.AgentBuilderHelper
import java.lang.instrument.Instrumentation

/**
 * KortexAgent - Java Agent for automated distributed tracing.
 * 
 * This agent uses ByteBuddy to instrument JDBC and HTTP calls,
 * capturing timing and context information for distributed tracing.
 */
object KortexAgent {
    
    /**
     * Premain method - entry point for Java agent.
     * Called by JVM when agent is loaded via -javaagent parameter.
     */
    @JvmStatic
    fun premain(agentArgs: String?, inst: Instrumentation) {
        println("[Kortex] Initializing Kortex Agent...")
        
        // Parse agent arguments for configuration
        val config = parseAgentArgs(agentArgs)
        val host = config["host"] ?: "localhost"
        val port = config["port"]?.toIntOrNull() ?: 9090
        
        // Initialize the span reporter
        SpanReporter.initialize(host, port)
        
        // Add shutdown hook to gracefully shutdown the reporter
        Runtime.getRuntime().addShutdownHook(Thread {
            println("[Kortex] Shutting down Kortex Agent...")
            SpanReporter.shutdown()
        })
        
        // Install ByteBuddy instrumentation
        AgentBuilderHelper.installAgent(inst)
        
        println("[Kortex] Kortex Agent initialized successfully")
        println("[Kortex] Reporting to: $host:$port")
        println("[Kortex] Instrumentation enabled for:")
        println("[Kortex]   - JDBC (PreparedStatement)")
        println("[Kortex]   - HTTP Server (Jakarta/Javax Servlet)")
        println("[Kortex]   - HTTP Client (java.net.http.HttpClient)")
    }
    
    /**
     * Parse agent arguments in format: key1=value1,key2=value2
     */
    private fun parseAgentArgs(agentArgs: String?): Map<String, String> {
        if (agentArgs.isNullOrBlank()) return emptyMap()
        
        return agentArgs.split(",")
            .mapNotNull { arg ->
                val parts = arg.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0].trim() to parts[1].trim()
                } else {
                    null
                }
            }
            .toMap()
    }
}
