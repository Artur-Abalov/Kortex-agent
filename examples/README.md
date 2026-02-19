# Kortex Agent Examples

This directory contains example applications demonstrating Kortex Agent usage.

## JDBC Example

Demonstrates automatic tracing of JDBC operations:

```bash
# 1. Build the agent
cd ..
./gradlew clean build

# 2. Download H2 database (example dependency)
wget https://repo1.maven.org/maven2/com/h2database/h2/2.2.224/h2-2.2.224.jar

# 3. Compile the example
javac -cp h2-2.2.224.jar examples/JdbcExample.java

# 4. Run with the agent
java -javaagent:build/libs/kortex-agent-1.0.0.jar=host=localhost,port=9090 \
     -cp h2-2.2.224.jar:examples io.kortex.example.JdbcExample
```

## What Gets Traced

The agent will automatically create spans for:

- **JDBC Operations**: Each `PreparedStatement.executeUpdate()` and `executeQuery()` call
- **Span Attributes**: SQL queries, operation types, execution times
- **Trace Context**: All operations within a thread share the same trace ID

## Expected Output

You should see:
1. Agent initialization messages
2. Your application output
3. Span reports being sent to Kortex Core (if running)

Example:
```
[Kortex] Initializing Kortex Agent...
[Kortex] SpanReporter initialized with endpoint localhost:9090
[Kortex] Kortex Agent initialized successfully
Starting JDBC Example with Kortex Agent
Inserted user 1
Inserted user 2
Inserted user 3
Inserted user 4
Inserted user 5

Users with ID > 2:
  ID: 3, Name: User 3
  ID: 4, Name: User 4
  ID: 5, Name: User 5

Done! Check Kortex Core for trace data.
[Kortex] Successfully sent batch of 7 spans
```

## Notes

- If Kortex Core is not running, spans will be queued but connection errors will be logged
- The agent is designed to fail gracefully - your application will continue to work even if tracing fails
- All tracing is done asynchronously to minimize performance impact
