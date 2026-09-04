package com.eventsphere.db;

import com.eventsphere.util.EnvLoader;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseManager {
    private static boolean isSqlite = false;
    private static String mysqlUrl;
    private static String mysqlUser;
    private static String mysqlPassword;
    private static String sqliteUrl;

    static {
        initDb();
    }

    private static void initDb() {
        String host = EnvLoader.get("DB_HOST", "localhost");
        String port = EnvLoader.get("DB_PORT", "3306");
        mysqlUser = EnvLoader.get("DB_USER", "root");
        mysqlPassword = EnvLoader.get("DB_PASSWORD", "rootpassword");
        String dbName = EnvLoader.get("DB_NAME", "event_management_db");

        mysqlUrl = "jdbc:mysql://" + host + ":" + port + "/" + dbName + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000";
        sqliteUrl = "jdbc:sqlite:event_management.db";

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection conn = DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword)) {
                System.out.println("==========================================================");
                System.out.println("Connected to MySQL Database successfully via JDBC!");
                System.out.println("==========================================================");
                isSqlite = false;
                return;
            }
        } catch (Exception e) {
            System.out.println("==========================================================");
            System.out.println("MySQL Connection failed. Credentials might be wrong.");
            System.out.println("FALLING BACK: Booting up local SQLite relational database.");
            System.out.println("==========================================================");
            initSqlite();
        }
    }

    private static void initSqlite() {
        try {
            Class.forName("org.sqlite.JDBC");
            isSqlite = true;
            File dbFile = new File("event_management.db");
            boolean dbExists = dbFile.exists();

            try (Connection conn = DriverManager.getConnection(sqliteUrl)) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA foreign_keys = ON;");
                    try {
                        stmt.execute("PRAGMA journal_mode = WAL;");
                        stmt.execute("PRAGMA synchronous = NORMAL;");
                        stmt.execute("PRAGMA temp_store = MEMORY;");
                        stmt.execute("PRAGMA cache_size = -64000;");
                    } catch (Exception ignore) {}
                }

                if (!dbExists) {
                    File schemaFile = new File("schema.sql");
                    if (schemaFile.exists()) {
                        String sql = new String(Files.readAllBytes(Paths.get("schema.sql")), StandardCharsets.UTF_8);
                        sql = sql.replaceAll("(?i)INT AUTO_INCREMENT PRIMARY KEY", "INTEGER PRIMARY KEY AUTOINCREMENT");
                        sql = sql.replaceAll("(?i)AUTO_INCREMENT", "AUTOINCREMENT");
                        sql = sql.replaceAll("(?i)ENUM\\([^)]+\\)", "TEXT");
                        sql = sql.replaceAll("(?i)UNIQUE KEY \\w+ \\(([^)]+)\\)", "UNIQUE($1)");

                        String[] statements = sql.split(";");
                        try (Statement stmt = conn.createStatement()) {
                            for (String s : statements) {
                                String trimmed = s.trim();
                                if (!trimmed.isEmpty()) {
                                    stmt.execute(trimmed);
                                }
                            }
                        }
                        System.out.println("==========================================================");
                        System.out.println("SQLite database \"event_management.db\" initialized & pre-seeded!");
                        System.out.println("==========================================================");
                    }
                } else {
                    System.out.println("==========================================================");
                    System.out.println("SQLite database \"event_management.db\" loaded successfully.");
                    System.out.println("==========================================================");
                }

                // Auto-migrate schema extensions & performance indexes if missing
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE TABLE IF NOT EXISTS Reviews (" +
                            "ReviewID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "EventID INTEGER NOT NULL, " +
                            "UserID INTEGER NOT NULL, " +
                            "Rating INTEGER NOT NULL CHECK (Rating >= 1 AND Rating <= 5), " +
                            "Comment TEXT, " +
                            "CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (EventID) REFERENCES Events(EventID) ON DELETE CASCADE, " +
                            "FOREIGN KEY (UserID) REFERENCES Users(UserID) ON DELETE CASCADE, " +
                            "UNIQUE(UserID, EventID));");
                    stmt.execute("CREATE TABLE IF NOT EXISTS UserInterests (" +
                            "InterestID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "UserID INTEGER NOT NULL, " +
                            "InterestTag TEXT NOT NULL, " +
                            "FOREIGN KEY (UserID) REFERENCES Users(UserID) ON DELETE CASCADE, " +
                            "UNIQUE(UserID, InterestTag));");
                    stmt.execute("CREATE TABLE IF NOT EXISTS Certificates (" +
                            "CertID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                            "BookingID INTEGER UNIQUE NOT NULL, " +
                            "CertHash TEXT UNIQUE NOT NULL, " +
                            "IssuedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "FOREIGN KEY (BookingID) REFERENCES Bookings(BookingID) ON DELETE CASCADE);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_date ON Events(Date, Time);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_events_organizer ON Events(OrganizerID);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_bookings_user ON Bookings(UserID);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_bookings_event ON Bookings(EventID);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_reviews_event ON Reviews(EventID);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_notifications_user ON Notifications(UserID, IsRead);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_user_interests ON UserInterests(UserID);");
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_cert_hash ON Certificates(CertHash);");
                    try {
                        stmt.execute("ALTER TABLE Bookings ADD COLUMN AttendanceStatus TEXT NOT NULL DEFAULT 'Registered';");
                    } catch (Exception ignore) {}
                    try {
                        stmt.execute("ALTER TABLE Users ADD COLUMN USN TEXT DEFAULT '1MS21CS042';");
                    } catch (Exception ignore) {}
                    try {
                        stmt.execute("ALTER TABLE Users ADD COLUMN Department TEXT DEFAULT 'Computer Science & Engineering';");
                    } catch (Exception ignore) {}
                }
            }
        } catch (Exception ex) {
            System.err.println("Failed to initialize SQLite database: " + ex.getMessage());
        }
    }

    public static Connection getConnection() throws Exception {
        if (isSqlite) {
            Connection conn = DriverManager.getConnection(sqliteUrl);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON;");
            }
            return conn;
        } else {
            return DriverManager.getConnection(mysqlUrl, mysqlUser, mysqlPassword);
        }
    }

    public static boolean isSqlite() {
        return isSqlite;
    }
}
