# Kortex Agent — Claude Code Context

## Project Overview

**Kortex Agent** is a Java instrumentation agent written in Kotlin that automatically traces JVM applications for distributed tracing without any code changes. It attaches via `-javaagent`, instruments JDBC and HTTP calls at bytecode level using [ByteBuddy](https://bytebuddy.net/), and reports structured spans to a Kortex Core backend over gRPC using the W3C Trace Context standard.

## Build & Test Commands

```bash
# Build the fat/shadow JAR (output: build/libs/kortex-agent-1.0.0.jar)
./gradlew clean build

# Run all tests (integration tests spawn child JVMs with the agent attached)
./gradlew test

# Build only (skip tests)
./gradlew shadowJar
```

> **Note:** Tests require the shadow JAR to be built first — the Gradle `test` task depends on `shadowJar` automatically.

## Project Structure

```
kortex-agent/
├── CLAUDE.md                              # This file
├── build.gradle.kts                       # Gradle build — Kotlin DSL
├── settings.gradle.kts
├── src/
│   ├── main/
│   │   ├── java/io/kortex/agent/internal/
│   │   │   ├── AdviceHelper.java           # ByteBuddy Advice factory (Java — needed for generic interop)
│   │   │   └── AgentBuilderHelper.java     # ByteBuddy AgentBuilder setup (Java)
│   │   ├── kotlin/io/kortex/agent/
│   │   │   ├── KortexAgent.kt             # Agent entry point — premain()
│   │   │   ├── ContextManager.kt          # ThreadLocal W3C trace context (trace ID, span ID)
│   │   │   ├── SpanReporter.kt            # Async span queue + gRPC sender
│   │   │   └── advice/
│   │   │       ├── JdbcAdvice.kt          # Instruments PreparedStatement.execute*
│   │   │       ├── HttpServerAdvice.kt    # Instruments HttpServlet.service/do*
│   │   │       └── HttpClientAdvice.kt    # Instruments HttpClient.send/sendAsync
│   │   └── proto/
│   │       └── span.proto                 # Protobuf schema for Span, ExportTraceServiceRequest, TraceService
│   └── test/
│       └── kotlin/io/kortex/agent/integration/
│           ├── AgentIntegrationTest.kt    # Black-box integration tests
│           ├── MockCollectorServer.kt     # In-process gRPC server that captures spans
│           ├── JdbcTestApp.kt             # Child-JVM test app: JDBC tracing
│           └── HttpPropagationTestApp.kt  # Child-JVM test app: W3C context propagation
└── examples/
    └── README.md
```

## Architecture

### Instrumentation Flow

1. JVM loads the agent via `-javaagent:kortex-agent-1.0.0.jar=host=<host>,port=<port>`
2. `KortexAgent.premain()` initialises `SpanReporter`, installs ByteBuddy transformers
3. ByteBuddy rewrites target classes **at class-load time** using the Advice pattern (compile-time code templates — no proxy, no reflection overhead)
4. Each advice class wraps target methods with `@Advice.OnMethodEnter` / `@Advice.OnMethodExit`
5. On exit, the advice builds a `Span` proto and calls `SpanReporter.reportSpan()`
6. A background daemon thread drains the `LinkedBlockingQueue<Span>` (capacity 10 000) and sends batches of up to 100 spans via `TraceService.Export(ExportTraceServiceRequest)` every 1 s or when the batch is full; the request is wrapped in the OTLP hierarchy `ExportTraceServiceRequest → ResourceSpans → ScopeSpans → Span`

### Why Java Helper Classes?

`AdviceHelper.java` and `AgentBuilderHelper.java` exist because Kotlin's type-inference engine fails to resolve ByteBuddy's deeply nested wildcard generic types (e.g., `AgentBuilder.Transformer` and `ElementMatcher` chains), producing **compilation errors** when these APIs are used directly from Kotlin. Keeping these two classes in Java avoids the problem entirely; **do not convert them to Kotlin**.

### ThreadLocal Stack Pattern

All advice classes use `ThreadLocal<ArrayDeque<Long>>` for start times and `ThreadLocal<ArrayDeque<String>>` for span IDs instead of `@Advice.Local`. This is intentional: `@Advice.Local` object-typed locals start as `null`, which causes `NPE` inside ByteBuddy before the value is propagated. The stack (deque) approach supports re-entrant calls on the same thread.

### Span Attributes

| Layer | Key Attributes |
|---|---|
| JDBC | `db.system`, `db.operation`, `db.statement` (SQL) |
| HTTP Server | `http.method`, `http.target`, `http.status_code`, `http.flavor` |
| HTTP Client | `http.method`, `http.url`, `http.status_code` |
| Errors (all) | `error=true`, `error.type`, `error.message` |

## Key Configuration

Agent arguments are passed as comma-separated `key=value` pairs:

```
-javaagent:kortex-agent-1.0.0.jar=host=localhost,port=9090
```

| Key | Default | Description |
|---|---|---|
| `host` | `localhost` | Kortex Core gRPC host |
| `port` | `9090` | Kortex Core gRPC port |

## Testing Approach

Integration tests (`AgentIntegrationTest.kt`) follow a black-box pattern:
1. Start a `MockCollectorServer` (in-process gRPC server) on a random port
2. Spawn a **child JVM** with `-javaagent` pointed at the mock collector
3. Child JVM runs a small test app (`JdbcTestApp`, `HttpPropagationTestApp`)
4. Parent process asserts on `MockCollectorServer.receivedSpans`

The agent JAR path is injected via the `agent.jar.path` system property — set automatically by Gradle.

## Coding Conventions

- **Kotlin** for all business logic; **Java** only where ByteBuddy generic interop requires it
- Error suppression in advice classes is deliberate — never let tracing crash the host app
- Protobuf-generated sources live in `build/generated/source/proto/` — do not edit them
- The shadow JAR must be self-contained; add new runtime dependencies to `implementation` (not `compileOnly`) in `build.gradle.kts`
