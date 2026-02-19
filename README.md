# Kortex Agent

A Java Agent written in Kotlin to provide automated distributed tracing for SQL (JDBC) and HTTP requests.

## Features

- **JDBC Instrumentation**: Automatically traces SQL queries executed via `PreparedStatement`
- **HTTP Server Tracing**: Captures incoming HTTP requests (Jakarta/Javax Servlet)
- **HTTP Client Tracing**: Traces outgoing HTTP requests (java.net.http.HttpClient)
- **W3C Trace Context**: Full support for W3C traceparent header propagation
- **Async Reporting**: Non-blocking span collection and batch reporting via gRPC
- **Zero Footprint**: Uses ByteBuddy Advice (code templates) for minimal overhead

## Building

Build the shadow JAR containing all dependencies:

```bash
./gradlew clean build
```

The agent JAR will be created at `build/libs/kortex-agent-1.0.0.jar`.

## Usage

Attach the agent to your Java application using the `-javaagent` flag:

```bash
java -javaagent:path/to/kortex-agent-1.0.0.jar=host=localhost,port=9090 -jar your-application.jar
```

### Agent Arguments

Configure the agent using comma-separated key=value pairs:

- `host`: Kortex Core gRPC server hostname (default: `localhost`)
- `port`: Kortex Core gRPC server port (default: `9090`)

Example:
```bash
java -javaagent:kortex-agent-1.0.0.jar=host=trace-server,port=8080 -jar app.jar
```

## Architecture

### Components

1. **KortexAgent** - Entry point with premain method that sets up ByteBuddy instrumentation
2. **ContextManager** - Thread-local trace context management with W3C traceparent support
3. **SpanReporter** - Async span reporter with batching and gRPC client
4. **Advice Classes** - ByteBuddy advice for JDBC, HTTP server, and HTTP client instrumentation

### Instrumentation Points

- **JDBC**: `java.sql.PreparedStatement.{execute, executeQuery, executeUpdate}`
- **HTTP Server**: `jakarta.servlet.http.HttpServlet.{service, doGet, doPost, doPut, doDelete}`
- **HTTP Client**: `java.net.http.HttpClient.{send, sendAsync}`

## How It Works

1. The agent is loaded by the JVM via the premain method
2. ByteBuddy sets up transformers for target classes
3. When instrumented methods are called, Advice code captures timing and context
4. Spans are queued in a LinkedBlockingQueue
5. A background daemon thread sends batches to Kortex Core via gRPC

## Span Data

Each span includes:

- **trace_id**: W3C-compliant 32-character hex trace ID
- **span_id**: 16-character hex span ID
- **parent_span_id**: Parent span ID for linking
- **name**: Operation name (e.g., "jdbc.executeQuery", "HTTP GET /api/users")
- **kind**: Span kind (DB, SERVER, CLIENT)
- **start_time_unix_nano**: Start timestamp in nanoseconds
- **end_time_unix_nano**: End timestamp in nanoseconds
- **attributes**: Key-value metadata (SQL query, HTTP method, status, etc.)
- **status**: OK or ERROR

## Development

### Prerequisites

- JDK 11 or higher
- Gradle 8.5+

### Project Structure

```
kortex-agent/
├── src/main/
│   ├── java/io/kortex/agent/internal/  # Java helpers for ByteBuddy
│   ├── kotlin/io/kortex/agent/         # Main Kotlin source
│   │   ├── advice/                     # Instrumentation advice
│   │   ├── ContextManager.kt
│   │   ├── KortexAgent.kt
│   │   └── SpanReporter.kt
│   └── proto/                          # Protobuf definitions
├── build.gradle.kts                    # Gradle build configuration
└── settings.gradle.kts
```

### Testing

To test the agent, create a simple application that uses JDBC and HTTP:

```java
import java.sql.*;
import java.net.http.*;

public class TestApp {
    public static void main(String[] args) throws Exception {
        // JDBC test
        Connection conn = DriverManager.getConnection("jdbc:h2:mem:test");
        PreparedStatement stmt = conn.prepareStatement("SELECT 1");
        stmt.executeQuery();
        
        // HTTP client test
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://example.com"))
            .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
```

Run with the agent:
```bash
java -javaagent:kortex-agent-1.0.0.jar TestApp
```

## License

Copyright © 2024