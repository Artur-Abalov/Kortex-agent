<div align="center">

```
╔═══════════════════════════════════════════════╗
║                                               ║
║   ██╗  ██╗ ██████╗ ██████╗ ████████╗███████╗ ║
║   ██║ ██╔╝██╔═══██╗██╔══██╗╚══██╔══╝██╔════╝ ║
║   █████╔╝ ██║   ██║██████╔╝   ██║   █████╗   ║
║   ██╔═██╗ ██║   ██║██╔══██╗   ██║   ██╔══╝   ║
║   ██║  ██╗╚██████╔╝██║  ██║   ██║   ███████╗ ║
║   ╚═╝  ╚═╝ ╚═════╝ ╚═╝  ╚═╝   ╚═╝   ╚══════╝ ║
║                    AGENT                      ║
╚═══════════════════════════════════════════════╝
```

**Zero-overhead distributed tracing for the JVM**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Java](https://img.shields.io/badge/JDK-11+-ED8B00?style=flat-square&logo=openjdk&logoColor=white)](https://openjdk.org)
[![gRPC](https://img.shields.io/badge/gRPC-transport-00ADD8?style=flat-square)](https://grpc.io)
[![W3C Trace Context](https://img.shields.io/badge/W3C-Trace%20Context-005A9C?style=flat-square)](https://www.w3.org/TR/trace-context/)
[![License](https://img.shields.io/badge/License-Proprietary-gray?style=flat-square)](./LICENSE)

</div>

---

## What Is Kortex Agent?

Kortex Agent is a **Java instrumentation agent** written in Kotlin that automatically instruments your JVM application for distributed tracing — with **no code changes required**. Attach it via `-javaagent` and it instantly begins capturing SQL queries, incoming HTTP requests, and outgoing HTTP calls, reporting structured spans to your Kortex Core backend via gRPC.

Built on [ByteBuddy](https://bytebuddy.net/), it injects tracing logic at the bytecode level using the Advice pattern, keeping runtime overhead minimal and your application code pristine.

---

## Features

| Capability | Details |
|---|---|
| 🗄️ **JDBC Instrumentation** | Traces all `PreparedStatement` executions — queries, updates, and batch operations |
| 🌐 **HTTP Server Tracing** | Captures incoming requests via Jakarta & Javax Servlet APIs |
| 🔗 **HTTP Client Tracing** | Instruments outgoing calls through `java.net.http.HttpClient` |
| 📡 **W3C Trace Context** | Full `traceparent` header propagation for end-to-end distributed traces |
| ⚡ **Async Reporting** | Non-blocking span collection via `LinkedBlockingQueue` with batched gRPC delivery |
| 🪶 **Zero Footprint** | ByteBuddy Advice (compile-time code templates) — no proxies, no reflection overhead |

---

## Quick Start

### 1. Build

```bash
./gradlew clean build
```

The agent JAR will be produced at:

```
build/libs/kortex-agent-1.0.0.jar
```

### 2. Attach

Prepend the `-javaagent` flag to your application's startup command:

```bash
java -javaagent:path/to/kortex-agent-1.0.0.jar=host=localhost,port=9090 \
     -jar your-application.jar
```

### 3. Configure

Pass configuration as comma-separated `key=value` pairs in the agent argument string:

| Argument | Default | Description |
|---|---|---|
| `host` | `localhost` | Hostname of the Kortex Core gRPC server |
| `port` | `9090` | Port of the Kortex Core gRPC server |

**Example — custom backend:**

```bash
java -javaagent:kortex-agent-1.0.0.jar=host=trace-server,port=8080 \
     -jar app.jar
```

---

## Span Data

Every captured operation is reported as a structured span:

```
┌─────────────────────────────────────────────────────────┐
│  Span                                                   │
├─────────────────────┬───────────────────────────────────┤
│ trace_id            │ 32-char hex  (W3C compliant)      │
│ span_id             │ 16-char hex                       │
│ parent_span_id      │ 16-char hex  (for linking)        │
│ name                │ "jdbc.executeQuery", "HTTP GET /…" │
│ kind                │ DB | SERVER | CLIENT              │
│ start_time_unix_nano│ nanosecond precision timestamp    │
│ end_time_unix_nano  │ nanosecond precision timestamp    │
│ attributes          │ SQL query, HTTP method, status…   │
│ status              │ OK | ERROR                        │
└─────────────────────┴───────────────────────────────────┘
```

---

## Architecture

```
Your Application
       │
       ▼
┌─────────────────────────────────────────────────────────┐
│                     JVM + Kortex Agent                  │
│                                                         │
│  ┌─────────────┐    ┌───────────────┐                   │
│  │ KortexAgent │───▶│  ByteBuddy    │  Transforms target │
│  │  (premain)  │    │  Transformers │  classes at load   │
│  └─────────────┘    └───────┬───────┘                   │
│                             │                           │
│              ┌──────────────┼──────────────┐            │
│              ▼              ▼              ▼            │
│       ┌────────────┐ ┌──────────┐ ┌─────────────┐      │
│       │ JDBC       │ │ HTTP     │ │ HTTP Client │      │
│       │ Advice     │ │ Server   │ │ Advice      │      │
│       └──────┬─────┘ │ Advice   │ └──────┬──────┘      │
│              │       └────┬─────┘        │              │
│              └────────────┼──────────────┘              │
│                           ▼                             │
│                  ┌────────────────┐                     │
│                  │ ContextManager │  Thread-local trace  │
│                  │ (W3C context)  │  propagation         │
│                  └───────┬────────┘                     │
│                          ▼                              │
│                  ┌────────────────┐                     │
│                  │  SpanReporter  │  LinkedBlockingQueue │
│                  │  (async batch) │  + daemon thread     │
│                  └───────┬────────┘                     │
└──────────────────────────│──────────────────────────────┘
                           │ gRPC
                           ▼
                  ┌────────────────┐
                  │  Kortex Core   │
                  └────────────────┘
```

### Instrumentation Points

| Layer | Intercepted Methods |
|---|---|
| **JDBC** | `PreparedStatement.execute`, `.executeQuery`, `.executeUpdate` |
| **HTTP Server** | `HttpServlet.service`, `.doGet`, `.doPost`, `.doPut`, `.doDelete` |
| **HTTP Client** | `HttpClient.send`, `.sendAsync` |

### How It Works

1. The JVM invokes `premain` before your application's `main` method
2. ByteBuddy installs transformers that rewrite matching classes at load time
3. Advice code surrounds each instrumented method, capturing start/end timestamps and context
4. Completed spans are enqueued into a `LinkedBlockingQueue` (non-blocking for your threads)
5. A background daemon thread drains the queue and sends batches to Kortex Core over gRPC

---

## Project Structure

```
kortex-agent/
├── src/main/
│   ├── java/io/kortex/agent/internal/   # Java helpers for ByteBuddy interop
│   ├── kotlin/io/kortex/agent/
│   │   ├── advice/                       # Instrumentation advice classes
│   │   │   ├── JdbcAdvice.kt
│   │   │   ├── HttpServerAdvice.kt
│   │   │   └── HttpClientAdvice.kt
│   │   ├── ContextManager.kt             # Thread-local W3C trace context
│   │   ├── KortexAgent.kt                # Agent entry point (premain)
│   │   └── SpanReporter.kt              # Async batching + gRPC client
│   └── proto/                            # Protobuf definitions
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Testing

Create a minimal test application that exercises both JDBC and HTTP:

```java
import java.sql.*;
import java.net.http.*;
import java.net.URI;

public class TestApp {
    public static void main(String[] args) throws Exception {
        // JDBC — will produce a DB span
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:test");
        PreparedStatement stmt = conn.prepareStatement("SELECT 1");
        stmt.executeQuery();

        // HTTP Client — will produce a CLIENT span
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://example.com"))
            .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

Run with the agent attached:

```bash
java -javaagent:kortex-agent-1.0.0.jar TestApp
```

---

## Prerequisites

- **JDK 11** or higher
- **Gradle 8.5+**

---

<div align="center">

Copyright © 2024 Kortex · All rights reserved

</div>
