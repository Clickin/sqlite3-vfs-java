package io.github.clickin.sqlitevfs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.Properties;

/**
 * Native SQLite is loaded only by this separate-process test entry point.
 * Start every connection before taking competing locks: startup reads the pragmas and version.
 */
public final class NativeOracle {
    private NativeOracle() {}

    public static void main(String[] args) throws Exception {
        // Select the native oracle explicitly even when the pure JVM driver is also on the classpath.
        try (Connection connection = new org.sqlite.JDBC().connect("jdbc:sqlite:" + args[0], new Properties());
             Statement statement = connection.createStatement();
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             PrintWriter output = new PrintWriter(System.out, true, StandardCharsets.UTF_8)) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA busy_timeout=0");
            statement.execute("PRAGMA synchronous=FULL");
            String mode = scalar(statement, "PRAGMA journal_mode");
            String busy = scalar(statement, "PRAGMA busy_timeout");
            String sync = scalar(statement, "PRAGMA synchronous");
            if (!mode.equalsIgnoreCase("delete") || !busy.equals("0") || !sync.equals("2")) {
                throw new IllegalStateException("Unexpected oracle pragmas: " + mode + "/" + busy + "/" + sync);
            }
            if (args[1].equals("init")) {
                statement.execute("CREATE TABLE sample(id INTEGER PRIMARY KEY, value INTEGER NOT NULL)");
                statement.execute("INSERT INTO sample VALUES (1, 10), (2, 20)");
            }
            output.println("READY\tnative\tSQLite=" + scalar(statement, "SELECT sqlite_version()")
                    + "\tsource_id=" + scalar(statement, "SELECT sqlite_source_id()")
                    + "\tJDBC=" + connection.getMetaData().getDriverVersion()
                    + "\tjournal_mode=" + mode + "\tbusy_timeout=" + busy + "\tsynchronous=" + sync);
            String line;
            while ((line = input.readLine()) != null) {
                String[] request = line.split("\t", 2);
                String sql = decode(request[1]);
                try {
                    String value = "";
                    if (statement.execute(sql)) {
                        try (ResultSet result = statement.getResultSet()) {
                            if (result.next()) {
                                value = result.getString(1);
                            }
                        }
                    }
                    reply(output, request[0], 0, value == null ? "" : value);
                } catch (SQLException exception) {
                    reply(output, request[0], exception.getErrorCode(), exception.getMessage());
                }
            }
        }
    }

    private static String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("No scalar result for " + sql);
            }
            return result.getString(1);
        }
    }

    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    static void reply(PrintWriter output, String id, int code, String value) {
        output.println(id + "\t" + code + "\t" + encode(value));
    }
}
