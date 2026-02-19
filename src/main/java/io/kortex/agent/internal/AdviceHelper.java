package io.kortex.agent.internal;

import io.kortex.agent.advice.HttpClientAdvice;
import io.kortex.agent.advice.HttpServerAdvice;
import io.kortex.agent.advice.JdbcAdvice;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.matcher.ElementMatcher;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Helper class to create ByteBuddy Advice instances.
 * This is needed because Kotlin has trouble with ByteBuddy's generic methods.
 */
public class AdviceHelper {
    
    public static AsmVisitorWrapper createJdbcAdvice() {
        return Advice.to(JdbcAdvice.class)
                .on(named("execute")
                        .or(named("executeQuery"))
                        .or(named("executeUpdate")));
    }
    
    public static AsmVisitorWrapper createHttpServerAdvice() {
        return Advice.to(HttpServerAdvice.class)
                .on(named("service")
                        .or(named("doGet"))
                        .or(named("doPost"))
                        .or(named("doPut"))
                        .or(named("doDelete")));
    }
    
    public static AsmVisitorWrapper createHttpClientAdvice() {
        return Advice.to(HttpClientAdvice.class)
                .on(named("send")
                        .or(named("sendAsync")));
    }
}
