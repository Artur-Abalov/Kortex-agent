package io.kortex.example;

import java.sql.*;

/**
 * Simple example showing Kortex Agent tracing JDBC operations.
 * 
 * Run with:
 * java -javaagent:kortex-agent-1.0.0.jar=host=localhost,port=9090 \
 *      -cp h2.jar:. io.kortex.example.JdbcExample
 */
public class JdbcExample {
    
    public static void main(String[] args) {
        System.out.println("Starting JDBC Example with Kortex Agent");
        
        try {
            // Create an in-memory H2 database
            Connection conn = DriverManager.getConnection("jdbc:h2:mem:test");
            
            // Create a table
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, name VARCHAR(255))");
            
            // Insert some data
            PreparedStatement insertStmt = conn.prepareStatement(
                "INSERT INTO users (id, name) VALUES (?, ?)"
            );
            
            for (int i = 1; i <= 5; i++) {
                insertStmt.setInt(1, i);
                insertStmt.setString(2, "User " + i);
                insertStmt.executeUpdate();
                System.out.println("Inserted user " + i);
            }
            
            // Query the data
            PreparedStatement queryStmt = conn.prepareStatement(
                "SELECT * FROM users WHERE id > ?"
            );
            queryStmt.setInt(1, 2);
            
            ResultSet rs = queryStmt.executeQuery();
            System.out.println("\nUsers with ID > 2:");
            while (rs.next()) {
                System.out.println("  ID: " + rs.getInt("id") + 
                                 ", Name: " + rs.getString("name"));
            }
            
            // Clean up
            rs.close();
            queryStmt.close();
            insertStmt.close();
            stmt.close();
            conn.close();
            
            System.out.println("\nDone! Check Kortex Core for trace data.");
            
            // Give the reporter time to send spans
            Thread.sleep(2000);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
