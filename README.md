<div align="center">

```
 _  _____  ____  ____  _____  _  _
| |/ / _ \|  _ \|_  _|| ____|| \/ |
| ' / | | | |_) | || | |  _|  >  <
| . \ |_| |  _ <  || | | |___/ /\ \
|_|\_\___/|_| \_\|___||_____/_/  \_\

            A  G  E  N  T
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

Every captured operation is reported as a structured span wrapped in the OTLP hierarchy:
`ExportTraceServiceRequest → ResourceSpans → ScopeSpans → Span`

### Resource

A **Resource** represents the entity producing the telemetry — typically the monitored service
itself. Attributes follow [OTel semantic conventions](https://opentelemetry.io/docs/specs/semconv/resource/),
e.g. `service.name`, `service.version`, `host.name`. Kortex Agent creates a Resource per
batch; by default it has no attributes (the host application should populate these at startup
via agent arguments in a future release).

### InstrumentationScope

The **InstrumentationScope** identifies the library that produced the telemetry. Kortex Agent
sets `name = "io.kortex.agent"` and `version = "1.0.0"` on every scope, making it easy to
filter spans by instrumentation library in your backend.

### Span

```
┌─────────────────────────────────────────────────────────┐
│  Span                                                   │
├─────────────────────┬───────────────────────────────────┤
│ trace_id            │ 16 bytes (128-bit, W3C compliant) │
│ span_id             │ 8 bytes  (64-bit)                 │
│ parent_span_id      │ 8 bytes  (for linking, optional)  │
│ name                │ "jdbc.executeQuery", "HTTP GET /…" │
│ kind                │ SERVER | CLIENT | INTERNAL | …    │
│ start_time_unix_nano│ nanosecond precision (fixed64)    │
│ end_time_unix_nano  │ nanosecond precision (fixed64)    │
│ attributes          │ repeated KeyValue (typed)         │
│ status              │ Status{code, message}             │
│ events              │ repeated Event (time-stamped)     │
│ links               │ repeated Link (cross-trace refs)  │
└─────────────────────┴───────────────────────────────────┘
```

### Typed Attributes (KeyValue / AnyValue)

Span attributes use the OTLP `repeated KeyValue` format instead of `map<string, string>`,
enabling **richer typed values** aligned with OTel semantic conventions:

| AnyValue type | Proto field | Example use |
|---|---|---|
| `string_value` | `string_value = 1` | `db.statement`, `http.method` |
| `bool_value`   | `bool_value   = 2` | feature flags |
| `int_value`    | `int_value    = 3` | `http.status_code` (numeric) |
| `double_value` | `double_value = 4` | latency measurements |
| `array_value`  | `array_value  = 5` | multi-value headers |
| `kvlist_value` | `kvlist_value = 6` | nested structured data |
| `bytes_value`  | `bytes_value  = 7` | binary payloads |

Kortex Agent currently populates string attributes; the typed schema allows backends to
store and query values without string parsing.

### Key Attributes by Layer

| Layer | Key Attributes |
|---|---|
| JDBC | `db.system`, `db.operation`, `db.statement` (sanitized SQL) |
| HTTP Server | `http.method`, `http.target`, `http.status_code`, `http.flavor` |
| HTTP Client | `http.method`, `http.url`, `http.status_code` |
| Errors (all) | `error=true`, `error.type`, `error.message` |

> **Note on `SPAN_KIND_DB` removal:** The non-standard `SPAN_KIND_DB` enum value has been
> removed to comply with the OTLP `SpanKind` definition. Database client spans now use
> `SPAN_KIND_CLIENT` (the application is the client to the database). Database-specific context
> is expressed via attributes such as `db.system` and `db.statement`, which is the correct
> pattern per [OTel DB semantic conventions](https://opentelemetry.io/docs/specs/semconv/database/).

### Status

Span outcome is encoded in a typed **Status** message instead of a plain string:

| Code | Meaning |
|---|---|
| `STATUS_CODE_UNSET` (0) | No explicit status; operation completed normally |
| `STATUS_CODE_OK` (1) | Operation completed successfully |
| `STATUS_CODE_ERROR` (2) | Operation failed — check `status.message` and `error.*` attributes |

### Events

**Events** are time-stamped annotations attached to a span. They share the same typed
`repeated KeyValue` attribute format as spans. Kortex Agent does not currently emit events,
but the field is present in the schema for backend and SDK compatibility.

### Links

**Links** connect a span to another span (potentially in a different trace), enabling
cross-trace relationships such as fan-in or async processing patterns. Each link carries
`trace_id`, `span_id`, optional `trace_state`, and typed attributes.

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
