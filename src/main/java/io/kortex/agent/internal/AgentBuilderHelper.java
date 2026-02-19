package io.kortex.agent.internal;

import net.bytebuddy.agent.builder.AgentBuilder;

import java.lang.instrument.Instrumentation;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * AgentBuilder helper for configuring ByteBuddy instrumentation.
 * This is needed because Kotlin has trouble with some of ByteBuddy's generic methods.
 */
public class AgentBuilderHelper {
    
    public static void installAgent(Instrumentation inst) {
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.Listener.NoOp.INSTANCE)
                .ignore(nameStartsWith("net.bytebuddy."))
                .ignore(nameStartsWith("io.kortex."))
                .ignore(nameStartsWith("java."))
                .ignore(nameStartsWith("javax."))
                .ignore(nameStartsWith("sun."))
                .ignore(nameStartsWith("com.sun."))
                .ignore(nameStartsWith("jdk."))
                .ignore(nameStartsWith("io.grpc."))
                .ignore(nameStartsWith("com.google."))
                .ignore(isSynthetic())
                // Install JDBC instrumentation
                .type(isSubTypeOf(java.sql.PreparedStatement.class))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(AdviceHelper.createJdbcAdvice()))
                // Install HTTP Server instrumentation (Jakarta Servlet)
                .type(named("jakarta.servlet.http.HttpServlet")
                        .or(named("javax.servlet.http.HttpServlet")))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(AdviceHelper.createHttpServerAdvice()))
                // Install HTTP Client instrumentation (java.net.http.HttpClient)
                .type(named("java.net.http.HttpClient"))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(AdviceHelper.createHttpClientAdvice()))
                .installOn(inst);
    }
}
