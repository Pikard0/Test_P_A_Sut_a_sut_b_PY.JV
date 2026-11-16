package ua.course.rental.flaky;

import ua.course.rental.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

public final class FlakyDb {
    private FlakyDb() {}

    public static Connection connect() throws Exception {
        String host = System.getenv().getOrDefault("DB_HOST", "localhost");
        String port = System.getenv().getOrDefault("DB_PORT", "5432");
        String name = System.getenv().getOrDefault("DB_NAME", "rental_variant13_test");
        String user = System.getenv().getOrDefault("DB_USER", "rental_student");
        String pass = System.getenv().getOrDefault("DB_PASSWORD", "");
        return DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + name, user, pass);
    }

    public static String createSchema(Connection connection, String prefix) throws Exception {
        String schema = prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "");
        try (var sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
        }
        connection.setSchema(schema);
        try (var sql = connection.createStatement()) {
            sql.execute(Files.readString(Path.of("migrations/V1__create_tables.sql")));
        }
        return schema;
    }

    public static void dropSchema(Connection connection, String schema) throws Exception {
        if (connection == null) return;
        try {
            try (var sql = connection.createStatement()) { sql.execute("ROLLBACK"); }
            connection.setSchema("public");
            if (schema != null) {
                try (var sql = connection.createStatement()) {
                    sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                }
            }
        } finally {
            connection.close();
        }
    }
}
