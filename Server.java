package com.eventsphere;

import com.eventsphere.db.DatabaseManager;
import com.eventsphere.util.EnvLoader;
import com.eventsphere.util.IpUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;

public class Server {
    private static int PORT;

    public static void main(String[] args) throws Exception {
        String portStr = EnvLoader.get("PORT", "5000");
        PORT = Integer.parseInt(portStr);

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // API Contexts
        server.createContext("/api/config", new ConfigHandler());
        server.createContext("/api/auth/register", new RegisterHandler());
        server.createContext("/api/auth/login", new LoginHandler());
        server.createContext("/api/events", new EventsHandler());
        server.createContext("/api/bookings", new BookingsHandler());
        server.createContext("/api/certificates", new CertificatesHandler());
        server.createContext("/api/admin/stats", new AdminStatsHandler());
        server.createContext("/api/notifications", new NotificationsHandler());

        // Static file handler for frontend UI
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        System.out.println("==========================================================");
        System.out.println("Event Management System (Java Backend) running on port " + PORT);
        System.out.println("API URL: http://localhost:" + PORT);
        System.out.println("Local Network IP: http://" + IpUtil.getLocalIpAddress() + ":" + PORT);
        System.out.println("==========================================================");
    }

    // Helper to send JSON response
    private static void sendJsonResponse(HttpExchange exchange, int statusCode, JSONObject jsonObj) throws IOException {
        byte[] responseBytes = jsonObj.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-User-ID");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static void sendJsonArrayResponse(HttpExchange exchange, int statusCode, JSONArray jsonArr) throws IOException {
        byte[] responseBytes = jsonArr.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-User-ID");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private static void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        JSONObject json = new JSONObject();
        json.put("error", message);
        sendJsonResponse(exchange, statusCode, json);
    }

    private static JSONObject parseRequestBody(HttpExchange exchange) throws IOException {
        InputStream is = exchange.getRequestBody();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        String bodyStr = sb.toString().trim();
        return bodyStr.isEmpty() ? new JSONObject() : new JSONObject(bodyStr);
    }

    private static JSONObject getAuthUser(HttpExchange exchange) throws Exception {
        String userIdStr = exchange.getRequestHeaders().getFirst("X-User-ID");
        if (userIdStr == null || userIdStr.trim().isEmpty()) {
            return null;
        }
        int userId = Integer.parseInt(userIdStr.trim());
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT UserID, Name, Email, Role FROM Users WHERE UserID = ?")) {
            stmt.setInt(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    JSONObject user = new JSONObject();
                    user.put("UserID", rs.getInt("UserID"));
                    user.put("Name", rs.getString("Name"));
                    user.put("Email", rs.getString("Email"));
                    user.put("Role", rs.getString("Role"));
                    return user;
                }
            }
        }
        return null;
    }

    private static void handleCorsPreflight(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-User-ID");
        exchange.sendResponseHeaders(204, -1);
    }

    // Helper Notification creators
    private static void createNotification(Connection conn, int userId, String message) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO Notifications (UserID, Message, IsRead) VALUES (?, ?, 0)")) {
            stmt.setInt(1, userId);
            stmt.setString(2, message);
            stmt.executeUpdate();
        }
    }

    private static void notifyAttendeesOfEvent(Connection conn, int eventId, String message) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT DISTINCT UserID FROM Bookings WHERE EventID = ? AND Status = 'Confirmed'")) {
            stmt.setInt(1, eventId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    createNotification(conn, rs.getInt("UserID"), message);
                }
            }
        }
    }

    private static void notifyAllAttendees(Connection conn, String message) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT UserID FROM Users WHERE Role = 'Attendee'")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    createNotification(conn, rs.getInt("UserID"), message);
                }
            }
        }
    }

    // Handlers
    static class ConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            String localIp = IpUtil.getLocalIpAddress();
            JSONObject json = new JSONObject();
            json.put("localIp", localIp);
            json.put("port", PORT);
            json.put("localUrl", "http://" + localIp + ":" + PORT);
            sendJsonResponse(exchange, 200, json);
        }
    }

    static class RegisterHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            try {
                JSONObject body = parseRequestBody(exchange);
                String name = body.optString("name", "").trim();
                String email = body.optString("email", "").trim();
                String password = body.optString("password", "").trim();
                String role = body.optString("role", "").trim();

                if (name.isEmpty() || email.isEmpty() || password.isEmpty() || role.isEmpty()) {
                    sendError(exchange, 400, "All fields (name, email, password, role) are required.");
                    return;
                }
                if (!role.equals("Admin") && !role.equals("Organizer") && !role.equals("Attendee")) {
                    sendError(exchange, 400, "Invalid role selection.");
                    return;
                }

                String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt(10));
                int newId = 0;

                try (Connection conn = DatabaseManager.getConnection()) {
                    String sql = "INSERT INTO Users (Name, Email, Password, Role) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                        stmt.setString(1, name);
                        stmt.setString(2, email);
                        stmt.setString(3, hashedPassword);
                        stmt.setString(4, role);
                        stmt.executeUpdate();

                        try (ResultSet rs = stmt.getGeneratedKeys()) {
                            if (rs.next()) {
                                newId = rs.getInt(1);
                            }
                        }
                    }
                }

                JSONObject res = new JSONObject();
                res.put("message", "Registration successful!");
                res.put("userId", newId);
                JSONObject user = new JSONObject();
                user.put("id", newId);
                user.put("name", name);
                user.put("email", email);
                user.put("role", role);
                res.put("user", user);

                sendJsonResponse(exchange, 201, res);

            } catch (SQLException sqle) {
                if (sqle.getMessage().contains("UNIQUE") || sqle.getErrorCode() == 1062) {
                    sendError(exchange, 400, "Email already registered.");
                } else {
                    sendError(exchange, 500, "Registration failed: " + sqle.getMessage());
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Registration server error: " + e.getMessage());
            }
        }
    }

    static class LoginHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Method Not Allowed");
                return;
            }
            try {
                JSONObject body = parseRequestBody(exchange);
                String email = body.optString("email", "").trim();
                String password = body.optString("password", "").trim();

                if (email.isEmpty() || password.isEmpty()) {
                    sendError(exchange, 400, "Email and password are required.");
                    return;
                }

                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT * FROM Users WHERE Email = ?")) {
                    stmt.setString(1, email);
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            sendError(exchange, 400, "Invalid Email or Password.");
                            return;
                        }
                        int userId = rs.getInt("UserID");
                        String dbName = rs.getString("Name");
                        String dbEmail = rs.getString("Email");
                        String dbPassword = rs.getString("Password");
                        String dbRole = rs.getString("Role");

                        boolean isMatch = false;
                        if (dbPassword.startsWith("$2a$") || dbPassword.startsWith("$2b$")) {
                            isMatch = BCrypt.checkpw(password, dbPassword);
                        } else {
                            isMatch = password.equals(dbPassword);
                        }

                        if (!isMatch) {
                            sendError(exchange, 400, "Invalid Email or Password.");
                            return;
                        }

                        JSONObject res = new JSONObject();
                        res.put("message", "Login successful!");
                        JSONObject user = new JSONObject();
                        user.put("id", userId);
                        user.put("name", dbName);
                        user.put("email", dbEmail);
                        user.put("role", dbRole);
                        res.put("user", user);

                        sendJsonResponse(exchange, 200, res);
                    }
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Server login error: " + e.getMessage());
            }
        }
    }

    static class EventsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            try {
                if (path.endsWith("/matchmaker")) {
                    String[] parts = path.split("/");
                    int eventId = Integer.parseInt(parts[3]);
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    int currentUserId = authUser.getInt("UserID");

                    JSONArray matches = new JSONArray();
                    try (Connection conn = DatabaseManager.getConnection()) {
                        Set<String> myTags = new HashSet<>();
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT InterestTag FROM UserInterests WHERE UserID = ?")) {
                            stmt.setInt(1, currentUserId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) myTags.add(rs.getString("InterestTag"));
                            }
                        }
                        if (myTags.isEmpty()) {
                            myTags.add("Python");
                            myTags.add("Algorithms");
                        }

                        String sql = "SELECT DISTINCT U.UserID, U.Name, U.Email, U.Role FROM Bookings B INNER JOIN Users U ON B.UserID = U.UserID WHERE B.EventID = ? AND B.Status = 'Confirmed' AND U.UserID != ?";
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setInt(1, eventId);
                            stmt.setInt(2, currentUserId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    int attId = rs.getInt("UserID");
                                    String attName = rs.getString("Name");
                                    String attEmail = rs.getString("Email");
                                    String attRole = rs.getString("Role");

                                    Set<String> attTags = new HashSet<>();
                                    try (PreparedStatement s2 = conn.prepareStatement("SELECT InterestTag FROM UserInterests WHERE UserID = ?")) {
                                        s2.setInt(1, attId);
                                        try (ResultSet rs2 = s2.executeQuery()) {
                                            while (rs2.next()) attTags.add(rs2.getString("InterestTag"));
                                        }
                                    }

                                    int sharedCount = 0;
                                    for (String t : attTags) {
                                        if (myTags.contains(t)) sharedCount++;
                                    }
                                    Set<String> union = new HashSet<>(myTags);
                                    union.addAll(attTags);
                                    double jaccardScore = union.isEmpty() ? 0 : ((double) sharedCount / union.size());
                                    int matchPercent = Math.min(98, (int) Math.round(60 + (jaccardScore * 38)));
                                    if (sharedCount == 0 && !attTags.isEmpty()) matchPercent = 75;

                                    JSONObject m = new JSONObject();
                                    m.put("userId", attId);
                                    m.put("name", attName);
                                    m.put("email", attEmail);
                                    m.put("role", attRole);
                                    m.put("matchPercent", matchPercent);
                                    JSONArray allTagsArr = new JSONArray();
                                    if (attTags.isEmpty()) {
                                        allTagsArr.put("Technology");
                                        allTagsArr.put("Problem Solving");
                                    } else {
                                        for (String t : attTags) allTagsArr.put(t);
                                    }
                                    m.put("allTags", allTagsArr);
                                    matches.put(m);
                                }
                            }
                        }
                    }
                    sendJsonArrayResponse(exchange, 200, matches);
                    return;
                } else if (path.endsWith("/reviews")) {
                    String[] parts = path.split("/");
                    int eventId = Integer.parseInt(parts[3]);
                    if (method.equals("GET")) {
                        JSONArray reviews = new JSONArray();
                        String sql = "SELECT R.ReviewID, R.Rating, R.Comment, R.CreatedAt, U.Name AS ReviewerName, U.Role AS ReviewerRole " +
                                     "FROM Reviews R INNER JOIN Users U ON R.UserID = U.UserID WHERE R.EventID = ? ORDER BY R.CreatedAt DESC";
                        double avgRating = 0.0;
                        int count = 0;

                        try (Connection conn = DatabaseManager.getConnection()) {
                            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                                stmt.setInt(1, eventId);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    while (rs.next()) {
                                        JSONObject r = new JSONObject();
                                        r.put("ReviewID", rs.getInt("ReviewID"));
                                        r.put("Rating", rs.getInt("Rating"));
                                        r.put("Comment", rs.getString("Comment"));
                                        r.put("CreatedAt", rs.getString("CreatedAt"));
                                        r.put("ReviewerName", rs.getString("ReviewerName"));
                                        r.put("ReviewerRole", rs.getString("ReviewerRole"));
                                        reviews.put(r);
                                    }
                                }
                            }
                            try (PreparedStatement s2 = conn.prepareStatement("SELECT AVG(Rating) AS avgRating, COUNT(*) AS reviewCount FROM Reviews WHERE EventID = ?")) {
                                s2.setInt(1, eventId);
                                try (ResultSet rs2 = s2.executeQuery()) {
                                    if (rs2.next()) {
                                        avgRating = rs2.getDouble("avgRating");
                                        count = rs2.getInt("reviewCount");
                                    }
                                }
                            }
                        }

                        JSONObject res = new JSONObject();
                        res.put("reviews", reviews);
                        res.put("avgRating", String.format("%.1f", avgRating));
                        res.put("reviewCount", count);
                        sendJsonResponse(exchange, 200, res);
                        return;

                    } else if (method.equals("POST")) {
                        JSONObject authUser = getAuthUser(exchange);
                        if (authUser == null) {
                            sendError(exchange, 401, "Unauthorized. Access Denied.");
                            return;
                        }
                        JSONObject body = parseRequestBody(exchange);
                        int rating = body.optInt("rating", 0);
                        String comment = body.optString("comment", "").trim();

                        if (rating < 1 || rating > 5) {
                            sendError(exchange, 400, "Rating must be between 1 and 5 stars.");
                            return;
                        }

                        try (Connection conn = DatabaseManager.getConnection()) {
                            int revId = 0;
                            try (PreparedStatement stmt = conn.prepareStatement("SELECT ReviewID FROM Reviews WHERE UserID = ? AND EventID = ?")) {
                                stmt.setInt(1, authUser.getInt("UserID"));
                                stmt.setInt(2, eventId);
                                try (ResultSet rs = stmt.executeQuery()) {
                                    if (rs.next()) revId = rs.getInt("ReviewID");
                                }
                            }

                            if (revId > 0) {
                                try (PreparedStatement stmt = conn.prepareStatement("UPDATE Reviews SET Rating = ?, Comment = ?, CreatedAt = CURRENT_TIMESTAMP WHERE ReviewID = ?")) {
                                    stmt.setInt(1, rating);
                                    stmt.setString(2, comment);
                                    stmt.setInt(3, revId);
                                    stmt.executeUpdate();
                                }
                                JSONObject res = new JSONObject();
                                res.put("message", "Your review has been updated successfully!");
                                sendJsonResponse(exchange, 200, res);
                            } else {
                                try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO Reviews (EventID, UserID, Rating, Comment) VALUES (?, ?, ?, ?)")) {
                                    stmt.setInt(1, eventId);
                                    stmt.setInt(2, authUser.getInt("UserID"));
                                    stmt.setInt(3, rating);
                                    stmt.setString(4, comment);
                                    stmt.executeUpdate();
                                }
                                JSONObject res = new JSONObject();
                                res.put("message", "Thank you! Your review has been submitted.");
                                sendJsonResponse(exchange, 201, res);
                            }
                        }
                        return;
                    }
                } else if (path.endsWith("/attendees")) {
                    String[] parts = path.split("/");
                    int eventId = Integer.parseInt(parts[3]);
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String role = authUser.getString("Role");
                    if (!role.equals("Organizer") && !role.equals("Admin")) {
                        sendError(exchange, 403, "Access forbidden for role: " + role);
                        return;
                    }

                    try (Connection conn = DatabaseManager.getConnection()) {
                        String eventTitle = "";
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT Title FROM Events WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) eventTitle = rs.getString("Title");
                            }
                        }

                        JSONArray attendees = new JSONArray();
                        String sql = "SELECT B.BookingID, B.BookingDate, B.Status, B.AttendanceStatus, U.UserID, U.Name, U.Email, U.Role, " +
                                     "COALESCE(P.PaymentMethod, 'N/A') AS PaymentMethod, COALESCE(P.TransactionID, 'N/A') AS TransactionID, " +
                                     "COALESCE(P.Amount, 0) AS AmountPaid " +
                                     "FROM Bookings B INNER JOIN Users U ON B.UserID = U.UserID " +
                                     "LEFT JOIN Payments P ON B.BookingID = P.BookingID " +
                                     "WHERE B.EventID = ? AND B.Status = 'Confirmed' ORDER BY U.Name ASC";
                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    JSONObject a = new JSONObject();
                                    a.put("BookingID", rs.getInt("BookingID"));
                                    a.put("BookingDate", rs.getString("BookingDate"));
                                    a.put("Status", rs.getString("Status"));
                                    String attStat = rs.getString("AttendanceStatus");
                                    a.put("AttendanceStatus", attStat != null ? attStat : "Registered");
                                    a.put("UserID", rs.getInt("UserID"));
                                    a.put("Name", rs.getString("Name"));
                                    a.put("Email", rs.getString("Email"));
                                    a.put("Role", rs.getString("Role"));
                                    a.put("PaymentMethod", rs.getString("PaymentMethod"));
                                    a.put("TransactionID", rs.getString("TransactionID"));
                                    a.put("AmountPaid", rs.getDouble("AmountPaid"));
                                    attendees.put(a);
                                }
                            }
                        }

                        JSONObject res = new JSONObject();
                        res.put("eventTitle", eventTitle);
                        res.put("attendees", attendees);
                        sendJsonResponse(exchange, 200, res);
                    }
                    return;
                } else if (method.equals("GET") && path.equals("/api/events")) {
                    JSONArray events = new JSONArray();
                    String sql = "SELECT E.*, U.Name AS OrganizerName FROM Events E INNER JOIN Users U ON E.OrganizerID = U.UserID ORDER BY E.Date ASC, E.Time ASC";
                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            JSONObject e = new JSONObject();
                            e.put("EventID", rs.getInt("EventID"));
                            e.put("Title", rs.getString("Title"));
                            e.put("Category", rs.getString("Category"));
                            e.put("Description", rs.getString("Description"));
                            e.put("Date", rs.getString("Date"));
                            e.put("Time", rs.getString("Time"));
                            e.put("Venue", rs.getString("Venue"));
                            e.put("TotalSlots", rs.getInt("TotalSlots"));
                            e.put("AvailableSlots", rs.getInt("AvailableSlots"));
                            e.put("Price", rs.getDouble("Price"));
                            e.put("OrganizerID", rs.getInt("OrganizerID"));
                            e.put("ImageURL", rs.getString("ImageURL"));
                            e.put("OrganizerName", rs.getString("OrganizerName"));
                            events.put(e);
                        }
                    }
                    sendJsonArrayResponse(exchange, 200, events);

                } else if (method.equals("POST")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String role = authUser.getString("Role");
                    if (!role.equals("Organizer") && !role.equals("Admin")) {
                        sendError(exchange, 403, "Access forbidden for role: " + role);
                        return;
                    }

                    JSONObject body = parseRequestBody(exchange);
                    String title = body.optString("title", "").trim();
                    String category = body.optString("category", "Academic").trim();
                    String description = body.optString("description", "").trim();
                    String date = body.optString("date", "").trim();
                    String time = body.optString("time", "").trim();
                    String venue = body.optString("venue", "").trim();
                    int totalSlots = body.optInt("totalSlots", 0);
                    double price = body.optDouble("price", 0.0);
                    String imageUrl = body.optString("imageUrl", null);
                    if (imageUrl != null && imageUrl.trim().isEmpty()) imageUrl = null;

                    if (title.isEmpty() || date.isEmpty() || time.isEmpty() || venue.isEmpty() || totalSlots <= 0) {
                        sendError(exchange, 400, "All fields (title, date, time, venue, totalSlots, price) are required.");
                        return;
                    }

                    int eventId = 0;
                    int organizerId = authUser.getInt("UserID");

                    try (Connection conn = DatabaseManager.getConnection()) {
                        String sql = "INSERT INTO Events (Title, Category, Description, Date, Time, Venue, TotalSlots, AvailableSlots, Price, OrganizerID, ImageURL) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                            stmt.setString(1, title);
                            stmt.setString(2, category.isEmpty() ? "Academic" : category);
                            stmt.setString(3, description);
                            stmt.setString(4, date);
                            stmt.setString(5, time);
                            stmt.setString(6, venue);
                            stmt.setInt(7, totalSlots);
                            stmt.setInt(8, totalSlots);
                            stmt.setDouble(9, price);
                            stmt.setInt(10, organizerId);
                            stmt.setString(11, imageUrl);
                            stmt.executeUpdate();

                            try (ResultSet rs = stmt.getGeneratedKeys()) {
                                if (rs.next()) eventId = rs.getInt(1);
                            }
                        }

                        notifyAllAttendees(conn, "A new event '" + title + "' (" + category + ") has been organized at " + venue + " on " + date + " at " + time + "! Book your tickets today.");
                    }

                    JSONObject res = new JSONObject();
                    res.put("message", "Event created successfully!");
                    JSONObject ev = new JSONObject();
                    ev.put("id", eventId);
                    ev.put("title", title);
                    ev.put("description", description);
                    ev.put("date", date);
                    ev.put("time", time);
                    ev.put("venue", venue);
                    ev.put("totalSlots", totalSlots);
                    ev.put("availableSlots", totalSlots);
                    ev.put("price", price);
                    ev.put("organizerId", organizerId);
                    ev.put("imageUrl", imageUrl);
                    res.put("event", ev);

                    sendJsonResponse(exchange, 201, res);

                } else if (method.equals("PUT")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String pathStr = path.substring("/api/events/".length());
                    int eventId = Integer.parseInt(pathStr);

                    JSONObject body = parseRequestBody(exchange);
                    String title = body.optString("title", "").trim();
                    String category = body.optString("category", "Academic").trim();
                    String description = body.optString("description", "").trim();
                    String date = body.optString("date", "").trim();
                    String time = body.optString("time", "").trim();
                    String venue = body.optString("venue", "").trim();
                    int totalSlots = body.optInt("totalSlots", 0);
                    double price = body.optDouble("price", 0.0);
                    String imageUrl = body.optString("imageUrl", null);
                    if (imageUrl != null && imageUrl.trim().isEmpty()) imageUrl = null;

                    try (Connection conn = DatabaseManager.getConnection()) {
                        conn.setAutoCommit(false);
                        int orgId = 0;
                        int prevAvailable = 0;

                        String selSql = DatabaseManager.isSqlite() ? "SELECT OrganizerID, AvailableSlots FROM Events WHERE EventID = ?" : "SELECT OrganizerID, AvailableSlots FROM Events WHERE EventID = ? FOR UPDATE";
                        try (PreparedStatement stmt = conn.prepareStatement(selSql)) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next()) {
                                    conn.rollback();
                                    sendError(exchange, 404, "Event not found.");
                                    return;
                                }
                                orgId = rs.getInt("OrganizerID");
                                prevAvailable = rs.getInt("AvailableSlots");
                            }
                        }

                        if (orgId != authUser.getInt("UserID") && !authUser.getString("Role").equals("Admin")) {
                            conn.rollback();
                            sendError(exchange, 403, "Access denied. You do not own this event.");
                            return;
                        }

                        int bookedCount = 0;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS count FROM Bookings WHERE EventID = ? AND Status = 'Confirmed'")) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) bookedCount = rs.getInt("count");
                            }
                        }

                        if (totalSlots < bookedCount) {
                            conn.rollback();
                            sendError(exchange, 400, "Cannot reduce total slots to " + totalSlots + ". There are already " + bookedCount + " confirmed bookings for this event.");
                            return;
                        }

                        int newAvailable = totalSlots - bookedCount;
                        String updSql = "UPDATE Events SET Title = ?, Category = ?, Description = ?, Date = ?, Time = ?, Venue = ?, TotalSlots = ?, AvailableSlots = ?, Price = ?, ImageURL = ? WHERE EventID = ?";
                        try (PreparedStatement stmt = conn.prepareStatement(updSql)) {
                            stmt.setString(1, title);
                            stmt.setString(2, category);
                            stmt.setString(3, description);
                            stmt.setString(4, date);
                            stmt.setString(5, time);
                            stmt.setString(6, venue);
                            stmt.setInt(7, totalSlots);
                            stmt.setInt(8, newAvailable);
                            stmt.setDouble(9, price);
                            stmt.setString(10, imageUrl);
                            stmt.setInt(11, eventId);
                            stmt.executeUpdate();
                        }

                        notifyAttendeesOfEvent(conn, eventId, "The event details for '" + title + "' have been updated by the organizer.");
                        if (prevAvailable <= 0 && newAvailable > 0) {
                            notifyAllAttendees(conn, "Tickets are now available for '" + title + "'! Book your seats today.");
                        }

                        conn.commit();
                        JSONObject res = new JSONObject();
                        res.put("message", "Event updated successfully!");
                        res.put("availableSlots", newAvailable);
                        sendJsonResponse(exchange, 200, res);
                    }

                } else if (method.equals("DELETE")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String pathStr = path.substring("/api/events/".length());
                    int eventId = Integer.parseInt(pathStr);

                    try (Connection conn = DatabaseManager.getConnection()) {
                        int orgId = 0;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT OrganizerID FROM Events WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next()) {
                                    sendError(exchange, 404, "Event not found.");
                                    return;
                                }
                                orgId = rs.getInt("OrganizerID");
                            }
                        }

                        if (orgId != authUser.getInt("UserID") && !authUser.getString("Role").equals("Admin")) {
                            sendError(exchange, 403, "Access denied. You do not own this event.");
                            return;
                        }

                        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM Events WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            stmt.executeUpdate();
                        }

                        JSONObject res = new JSONObject();
                        res.put("message", "Event and all associated bookings deleted successfully.");
                        sendJsonResponse(exchange, 200, res);
                    }
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Event Endpoint Error: " + e.getMessage());
            }
        }
    }

    static class BookingsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            try {
                // PUT /api/bookings/{id}/checkin
                if (method.equals("PUT") && path.endsWith("/checkin")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String[] parts = path.split("/");
                    int bookingId = Integer.parseInt(parts[3]);
                    JSONObject body = parseRequestBody(exchange);
                    String status = body.optString("status", "Registered");
                    String newStatus = (status.equals("Checked-In") || status.equals("Checked In")) ? "Checked-In" : "Registered";

                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement("UPDATE Bookings SET AttendanceStatus = ? WHERE BookingID = ?")) {
                        stmt.setString(1, newStatus);
                        stmt.setInt(2, bookingId);
                        stmt.executeUpdate();
                    }

                    JSONObject res = new JSONObject();
                    res.put("message", "Attendance updated to " + newStatus);
                    res.put("status", newStatus);
                    sendJsonResponse(exchange, 200, res);
                    return;
                }

                // GET /api/bookings/verify/{bookingId}
                if (method.equals("GET") && path.startsWith("/api/bookings/verify/")) {
                    String bookingIdStr = path.substring("/api/bookings/verify/".length());
                    int bookingId = Integer.parseInt(bookingIdStr);

                    String sql = "SELECT B.BookingID, B.Status AS BookingStatus, B.BookingDate, " +
                                 "E.Title AS EventTitle, E.Date AS EventDate, E.Time AS EventTime, E.Venue, E.Price, E.Category, " +
                                 "U.Name AS AttendeeName, U.Email AS AttendeeEmail " +
                                 "FROM Bookings B " +
                                 "INNER JOIN Events E ON B.EventID = E.EventID " +
                                 "INNER JOIN Users U ON B.UserID = U.UserID " +
                                 "WHERE B.BookingID = ?";

                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, bookingId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (!rs.next()) {
                                sendError(exchange, 404, "Ticket reference code is invalid or does not exist.");
                                return;
                            }
                            JSONObject b = new JSONObject();
                            b.put("BookingID", rs.getInt("BookingID"));
                            b.put("BookingStatus", rs.getString("BookingStatus"));
                            b.put("BookingDate", rs.getString("BookingDate"));
                            b.put("EventTitle", rs.getString("EventTitle"));
                            b.put("EventDate", rs.getString("EventDate"));
                            b.put("EventTime", rs.getString("EventTime"));
                            b.put("Venue", rs.getString("Venue"));
                            b.put("Price", rs.getDouble("Price"));
                            b.put("Category", rs.getString("Category"));
                            b.put("AttendeeName", rs.getString("AttendeeName"));
                            b.put("AttendeeEmail", rs.getString("AttendeeEmail"));
                            sendJsonResponse(exchange, 200, b);
                            return;
                        }
                    }
                }

                // GET /api/bookings/my
                if (method.equals("GET") && path.equals("/api/bookings/my")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    JSONArray bookings = new JSONArray();
                    String sql = "SELECT B.BookingID, B.BookingDate, B.Status, E.EventID, E.Title, E.Date, E.Time, E.Venue, E.Price, U.Name AS OrganizerName " +
                                 "FROM Bookings B " +
                                 "INNER JOIN Events E ON B.EventID = E.EventID " +
                                 "INNER JOIN Users U ON E.OrganizerID = U.UserID " +
                                 "WHERE B.UserID = ? ORDER BY B.BookingDate DESC";

                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, authUser.getInt("UserID"));
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                JSONObject b = new JSONObject();
                                b.put("BookingID", rs.getInt("BookingID"));
                                b.put("BookingDate", rs.getString("BookingDate"));
                                b.put("Status", rs.getString("Status"));
                                b.put("EventID", rs.getInt("EventID"));
                                b.put("Title", rs.getString("Title"));
                                b.put("Date", rs.getString("Date"));
                                b.put("Time", rs.getString("Time"));
                                b.put("Venue", rs.getString("Venue"));
                                b.put("Price", rs.getDouble("Price"));
                                b.put("OrganizerName", rs.getString("OrganizerName"));
                                bookings.put(b);
                            }
                        }
                    }
                    sendJsonArrayResponse(exchange, 200, bookings);
                    return;
                }

                // POST /api/bookings (Book Ticket - ACID Transaction)
                if (method.equals("POST") && path.equals("/api/bookings")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    JSONObject body = parseRequestBody(exchange);
                    int eventId = body.optInt("eventId", 0);
                    String paymentMethod = body.optString("paymentMethod", "").trim();
                    String transactionId = body.optString("transactionId", "").trim();

                    if (eventId <= 0) {
                        sendError(exchange, 400, "Event ID is required.");
                        return;
                    }

                    try (Connection conn = DatabaseManager.getConnection()) {
                        conn.setAutoCommit(false);

                        String selSql = DatabaseManager.isSqlite() ? "SELECT Title, AvailableSlots, TotalSlots, Price, OrganizerID FROM Events WHERE EventID = ?" : "SELECT Title, AvailableSlots, TotalSlots, Price, OrganizerID FROM Events WHERE EventID = ? FOR UPDATE";
                        String eventTitle = "";
                        int availableSlots = 0;
                        double price = 0.0;
                        int organizerId = 0;

                        try (PreparedStatement stmt = conn.prepareStatement(selSql)) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next()) {
                                    conn.rollback();
                                    sendError(exchange, 404, "Event not found.");
                                    return;
                                }
                                eventTitle = rs.getString("Title");
                                availableSlots = rs.getInt("AvailableSlots");
                                price = rs.getDouble("Price");
                                organizerId = rs.getInt("OrganizerID");
                            }
                        }

                        if (availableSlots <= 0) {
                            conn.rollback();
                            sendError(exchange, 400, "This event is fully booked! No slots available.");
                            return;
                        }

                        int existingBookingId = 0;
                        String existingStatus = "";
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT BookingID, Status FROM Bookings WHERE UserID = ? AND EventID = ?")) {
                            stmt.setInt(1, authUser.getInt("UserID"));
                            stmt.setInt(2, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    existingBookingId = rs.getInt("BookingID");
                                    existingStatus = rs.getString("Status");
                                }
                            }
                        }

                        int bookingId = 0;
                        if (existingBookingId > 0) {
                            if (existingStatus.equals("Confirmed")) {
                                conn.rollback();
                                sendError(exchange, 400, "You have already booked a ticket for this event.");
                                return;
                            } else {
                                try (PreparedStatement stmt = conn.prepareStatement("UPDATE Bookings SET Status = 'Confirmed', BookingDate = CURRENT_TIMESTAMP WHERE BookingID = ?")) {
                                    stmt.setInt(1, existingBookingId);
                                    stmt.executeUpdate();
                                }
                                bookingId = existingBookingId;
                            }
                        } else {
                            try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO Bookings (UserID, EventID, Status) VALUES (?, ?, 'Confirmed')", Statement.RETURN_GENERATED_KEYS)) {
                                stmt.setInt(1, authUser.getInt("UserID"));
                                stmt.setInt(2, eventId);
                                stmt.executeUpdate();
                                try (ResultSet rs = stmt.getGeneratedKeys()) {
                                    if (rs.next()) bookingId = rs.getInt(1);
                                }
                            }
                        }

                        // Decrement Available Slots
                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE Events SET AvailableSlots = AvailableSlots - 1 WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            stmt.executeUpdate();
                        }

                        // Insert Payment
                        String payMethod = paymentMethod.isEmpty() ? (price > 0 ? "UPI" : "Free") : paymentMethod;
                        String txnId = transactionId.isEmpty() ? "TXN-" + payMethod.toUpperCase() + "-" + System.currentTimeMillis() + "-" + (1000 + new Random().nextInt(9000)) : transactionId;

                        try (PreparedStatement stmt = conn.prepareStatement("INSERT INTO Payments (BookingID, Amount, PaymentMethod, TransactionID, Status) VALUES (?, ?, ?, ?, 'Paid')")) {
                            stmt.setInt(1, bookingId);
                            stmt.setDouble(2, price);
                            stmt.setString(3, payMethod);
                            stmt.setString(4, txnId);
                            stmt.executeUpdate();
                        }

                        // Notifications
                        createNotification(conn, authUser.getInt("UserID"), "Your booking for '" + eventTitle + "' has been confirmed! Price paid: ₹" + String.format("%.2f", price) + ".");
                        createNotification(conn, organizerId, "Attendee '" + authUser.getString("Name") + "' has successfully booked a ticket for your event '" + eventTitle + "'.");

                        try (PreparedStatement stmt = conn.prepareStatement("SELECT UserID FROM Users WHERE Role = 'Admin'")) {
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    createNotification(conn, rs.getInt("UserID"), "Attendee '" + authUser.getString("Name") + "' has booked a ticket for event '" + eventTitle + "' (Organizer ID: " + organizerId + ").");
                                }
                            }
                        }

                        conn.commit();
                        JSONObject res = new JSONObject();
                        res.put("message", "Ticket booked successfully! Booking confirmed.");
                        sendJsonResponse(exchange, 201, res);
                    }
                    return;
                }

                // POST /api/bookings/cancel (Cancel Booking)
                if (method.equals("POST") && path.equals("/api/bookings/cancel")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    JSONObject body = parseRequestBody(exchange);
                    int bookingId = body.optInt("bookingId", 0);

                    if (bookingId <= 0) {
                        sendError(exchange, 400, "Booking ID is required.");
                        return;
                    }

                    try (Connection conn = DatabaseManager.getConnection()) {
                        conn.setAutoCommit(false);
                        int eventId = 0;
                        String status = "";

                        String selSql = DatabaseManager.isSqlite() ? "SELECT EventID, Status FROM Bookings WHERE BookingID = ? AND UserID = ?" : "SELECT EventID, Status FROM Bookings WHERE BookingID = ? AND UserID = ? FOR UPDATE";
                        try (PreparedStatement stmt = conn.prepareStatement(selSql)) {
                            stmt.setInt(1, bookingId);
                            stmt.setInt(2, authUser.getInt("UserID"));
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next()) {
                                    conn.rollback();
                                    sendError(exchange, 404, "Booking not found or not owned by you.");
                                    return;
                                }
                                eventId = rs.getInt("EventID");
                                status = rs.getString("Status");
                            }
                        }

                        if (status.equals("Cancelled")) {
                            conn.rollback();
                            sendError(exchange, 400, "This booking is already cancelled.");
                            return;
                        }

                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE Bookings SET Status = 'Cancelled' WHERE BookingID = ?")) {
                            stmt.setInt(1, bookingId);
                            stmt.executeUpdate();
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE Events SET AvailableSlots = AvailableSlots + 1 WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            stmt.executeUpdate();
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE Payments SET Status = 'Refunded' WHERE BookingID = ?")) {
                            stmt.setInt(1, bookingId);
                            stmt.executeUpdate();
                        }

                        String eventTitle = "";
                        int availableSlots = 0;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT Title, AvailableSlots FROM Events WHERE EventID = ?")) {
                            stmt.setInt(1, eventId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) {
                                    eventTitle = rs.getString("Title");
                                    availableSlots = rs.getInt("AvailableSlots");
                                }
                            }
                        }

                        double refundAmount = 0.0;
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT Amount FROM Payments WHERE BookingID = ?")) {
                            stmt.setInt(1, bookingId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) refundAmount = rs.getDouble("Amount");
                            }
                        }

                        createNotification(conn, authUser.getInt("UserID"), "Your booking for '" + eventTitle + "' has been cancelled. A refund of ₹" + String.format("%.2f", refundAmount) + " has been initiated.");
                        if (availableSlots == 0) {
                            notifyAllAttendees(conn, "Tickets are now available for '" + eventTitle + "'! Book your seats today.");
                        }

                        conn.commit();
                        JSONObject res = new JSONObject();
                        res.put("message", "Booking cancelled successfully. Ticket released.");
                        sendJsonResponse(exchange, 200, res);
                    }
                    return;
                }

                sendError(exchange, 405, "Method Not Allowed");
            } catch (Exception e) {
                sendError(exchange, 500, "Bookings Endpoint Error: " + e.getMessage());
            }
        }
    }

    static class AdminStatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            try {
                JSONObject authUser = getAuthUser(exchange);
                if (authUser == null) {
                    sendError(exchange, 401, "Unauthorized. Access Denied.");
                    return;
                }
                int userId = authUser.getInt("UserID");
                String userRole = authUser.getString("Role");

                int totalUsers = 0, totalEvents = 0, activeBookings = 0;
                double totalRevenue = 0.0;
                JSONArray roleStats = new JSONArray();
                JSONArray eventPopularity = new JSONArray();
                JSONArray detailedReports = new JSONArray();

                try (Connection conn = DatabaseManager.getConnection()) {
                    if (userRole.equals("Admin")) {
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM Users")) {
                            if (rs.next()) totalUsers = rs.getInt("count");
                        }
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM Events")) {
                            if (rs.next()) totalEvents = rs.getInt("count");
                        }
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM Bookings WHERE Status = 'Confirmed'")) {
                            if (rs.next()) activeBookings = rs.getInt("count");
                        }
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT SUM(Amount) AS total FROM Payments WHERE Status = 'Paid'")) {
                            if (rs.next()) totalRevenue = rs.getDouble("total");
                        }
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT Role, COUNT(*) AS count FROM Users GROUP BY Role")) {
                            while (rs.next()) {
                                JSONObject r = new JSONObject();
                                r.put("Role", rs.getString("Role"));
                                r.put("count", rs.getInt("count"));
                                roleStats.put(r);
                            }
                        }
                        String popSql = "SELECT E.Title, COUNT(B.BookingID) AS BookingsCount FROM Events E LEFT JOIN Bookings B ON E.EventID = B.EventID AND B.Status = 'Confirmed' GROUP BY E.EventID, E.Title ORDER BY BookingsCount DESC LIMIT 5";
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(popSql)) {
                            while (rs.next()) {
                                JSONObject p = new JSONObject();
                                p.put("Title", rs.getString("Title"));
                                p.put("BookingsCount", rs.getInt("BookingsCount"));
                                eventPopularity.put(p);
                            }
                        }
                        String repSql = "SELECT E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots, " +
                                       "COUNT(CASE WHEN B.Status = 'Confirmed' THEN 1 END) AS BookingsCount, " +
                                       "COALESCE(SUM(CASE WHEN B.Status = 'Confirmed' AND P.Status = 'Paid' THEN P.Amount ELSE 0 END), 0) AS Revenue " +
                                       "FROM Events E " +
                                       "LEFT JOIN Bookings B ON E.EventID = B.EventID " +
                                       "LEFT JOIN Payments P ON B.BookingID = P.BookingID " +
                                       "GROUP BY E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots ORDER BY BookingsCount DESC";
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(repSql)) {
                            while (rs.next()) {
                                JSONObject d = new JSONObject();
                                d.put("EventID", rs.getInt("EventID"));
                                d.put("Title", rs.getString("Title"));
                                d.put("Price", rs.getDouble("Price"));
                                d.put("TotalSlots", rs.getInt("TotalSlots"));
                                d.put("AvailableSlots", rs.getInt("AvailableSlots"));
                                d.put("BookingsCount", rs.getInt("BookingsCount"));
                                d.put("Revenue", rs.getDouble("Revenue"));
                                detailedReports.put(d);
                            }
                        }

                    } else if (userRole.equals("Organizer")) {
                        String uSql = "SELECT COUNT(DISTINCT B.UserID) AS count FROM Bookings B INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND B.Status = 'Confirmed'";
                        try (PreparedStatement stmt = conn.prepareStatement(uSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) totalUsers = rs.getInt("count");
                            }
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS count FROM Events WHERE OrganizerID = ?")) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) totalEvents = rs.getInt("count");
                            }
                        }
                        String bSql = "SELECT COUNT(*) AS count FROM Bookings B INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND B.Status = 'Confirmed'";
                        try (PreparedStatement stmt = conn.prepareStatement(bSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) activeBookings = rs.getInt("count");
                            }
                        }
                        String revSql = "SELECT SUM(P.Amount) AS total FROM Payments P INNER JOIN Bookings B ON P.BookingID = B.BookingID INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND P.Status = 'Paid'";
                        try (PreparedStatement stmt = conn.prepareStatement(revSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) totalRevenue = rs.getDouble("total");
                            }
                        }
                        String rSql = "SELECT U.Role, COUNT(DISTINCT U.UserID) AS count FROM Users U INNER JOIN Bookings B ON U.UserID = B.UserID INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND B.Status = 'Confirmed' GROUP BY U.Role";
                        try (PreparedStatement stmt = conn.prepareStatement(rSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    JSONObject r = new JSONObject();
                                    r.put("Role", rs.getString("Role"));
                                    r.put("count", rs.getInt("count"));
                                    roleStats.put(r);
                                }
                            }
                        }
                        String popSql = "SELECT E.Title, COUNT(B.BookingID) AS BookingsCount FROM Events E LEFT JOIN Bookings B ON E.EventID = B.EventID AND B.Status = 'Confirmed' WHERE E.OrganizerID = ? GROUP BY E.EventID, E.Title ORDER BY BookingsCount DESC LIMIT 5";
                        try (PreparedStatement stmt = conn.prepareStatement(popSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    JSONObject p = new JSONObject();
                                    p.put("Title", rs.getString("Title"));
                                    p.put("BookingsCount", rs.getInt("BookingsCount"));
                                    eventPopularity.put(p);
                                }
                            }
                        }
                        String repSql = "SELECT E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots, " +
                                       "COUNT(CASE WHEN B.Status = 'Confirmed' THEN 1 END) AS BookingsCount, " +
                                       "COALESCE(SUM(CASE WHEN B.Status = 'Confirmed' AND P.Status = 'Paid' THEN P.Amount ELSE 0 END), 0) AS Revenue " +
                                       "FROM Events E " +
                                       "LEFT JOIN Bookings B ON E.EventID = B.EventID " +
                                       "LEFT JOIN Payments P ON B.BookingID = P.BookingID " +
                                       "WHERE E.OrganizerID = ? GROUP BY E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots ORDER BY BookingsCount DESC";
                        try (PreparedStatement stmt = conn.prepareStatement(repSql)) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    JSONObject d = new JSONObject();
                                    d.put("EventID", rs.getInt("EventID"));
                                    d.put("Title", rs.getString("Title"));
                                    d.put("Price", rs.getDouble("Price"));
                                    d.put("TotalSlots", rs.getInt("TotalSlots"));
                                    d.put("AvailableSlots", rs.getInt("AvailableSlots"));
                                    d.put("BookingsCount", rs.getInt("BookingsCount"));
                                    d.put("Revenue", rs.getDouble("Revenue"));
                                    detailedReports.put(d);
                                }
                            }
                        }

                    } else { // Attendee
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM Users")) {
                            if (rs.next()) totalUsers = rs.getInt("count");
                        }
                        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) AS count FROM Events")) {
                            if (rs.next()) totalEvents = rs.getInt("count");
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) AS count FROM Bookings WHERE UserID = ? AND Status = 'Confirmed'")) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) activeBookings = rs.getInt("count");
                            }
                        }
                        try (PreparedStatement stmt = conn.prepareStatement("SELECT SUM(P.Amount) AS total FROM Payments P INNER JOIN Bookings B ON P.BookingID = B.BookingID WHERE B.UserID = ? AND P.Status = 'Paid'")) {
                            stmt.setInt(1, userId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (rs.next()) totalRevenue = rs.getDouble("total");
                            }
                        }
                    }
                }

                JSONObject summary = new JSONObject();
                summary.put("totalUsers", totalUsers);
                summary.put("totalEvents", totalEvents);
                summary.put("activeBookings", activeBookings);
                summary.put("totalRevenue", totalRevenue);

                JSONObject res = new JSONObject();
                res.put("summary", summary);
                res.put("roleStats", roleStats);
                res.put("eventPopularity", eventPopularity);
                res.put("detailedReports", detailedReports);

                sendJsonResponse(exchange, 200, res);
            } catch (Exception e) {
                sendError(exchange, 500, "Admin Stats Error: " + e.getMessage());
            }
        }
    }

    static class NotificationsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            try {
                JSONObject authUser = getAuthUser(exchange);
                if (authUser == null) {
                    sendError(exchange, 401, "Unauthorized. Access Denied.");
                    return;
                }

                if (method.equals("GET")) {
                    JSONArray notifs = new JSONArray();
                    String sql = "SELECT * FROM Notifications WHERE UserID = ? ORDER BY CreatedAt DESC LIMIT 50";
                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setInt(1, authUser.getInt("UserID"));
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                JSONObject n = new JSONObject();
                                n.put("NotificationID", rs.getInt("NotificationID"));
                                n.put("UserID", rs.getInt("UserID"));
                                n.put("Message", rs.getString("Message"));
                                n.put("IsRead", rs.getInt("IsRead") == 1);
                                n.put("CreatedAt", rs.getString("CreatedAt"));
                                notifs.put(n);
                            }
                        }
                    }
                    sendJsonArrayResponse(exchange, 200, notifs);

                } else if (method.equals("POST") && path.equals("/api/notifications/read")) {
                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement("UPDATE Notifications SET IsRead = 1 WHERE UserID = ?")) {
                        stmt.setInt(1, authUser.getInt("UserID"));
                        stmt.executeUpdate();
                    }
                    JSONObject res = new JSONObject();
                    res.put("message", "Notifications marked as read.");
                    sendJsonResponse(exchange, 200, res);
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Notifications Error: " + e.getMessage());
            }
        }
    }

    static class CertificatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleCorsPreflight(exchange);
                return;
            }
            String method = exchange.getRequestMethod().toUpperCase();
            String path = exchange.getRequestURI().getPath();

            try {
                if (method.equals("GET") && path.startsWith("/api/certificates/verify/")) {
                    String hash = path.substring("/api/certificates/verify/".length());
                    String sql = "SELECT C.CertID, C.CertHash, C.IssuedAt, B.BookingID, B.AttendanceStatus, E.Title AS EventTitle, E.Category, E.Date AS EventDate, E.Venue, U.Name AS AttendeeName, U.Email AS AttendeeEmail, Org.Name AS OrganizerName FROM Certificates C INNER JOIN Bookings B ON C.BookingID = B.BookingID INNER JOIN Events E ON B.EventID = E.EventID INNER JOIN Users U ON B.UserID = U.UserID INNER JOIN Users Org ON E.OrganizerID = Org.UserID WHERE C.CertHash = ? OR C.BookingID = ?";
                    int numId = 0;
                    try { numId = Integer.parseInt(hash.replaceAll("\\D", "")); } catch (Exception ignore) {}

                    try (Connection conn = DatabaseManager.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(sql)) {
                        stmt.setString(1, hash);
                        stmt.setInt(2, numId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (!rs.next()) {
                                sendError(exchange, 404, "Certificate SHA-256 hash or Certificate ID is invalid.");
                                return;
                            }
                            JSONObject c = new JSONObject();
                            c.put("verified", true);
                            c.put("certId", "CERT-ES-" + rs.getInt("BookingID"));
                            c.put("certHash", rs.getString("CertHash"));
                            c.put("issuedAt", rs.getString("IssuedAt"));
                            c.put("attendeeName", rs.getString("AttendeeName"));
                            c.put("attendeeEmail", rs.getString("AttendeeEmail"));
                            c.put("eventTitle", rs.getString("EventTitle"));
                            c.put("category", rs.getString("Category"));
                            c.put("eventDate", rs.getString("EventDate"));
                            c.put("venue", rs.getString("Venue"));
                            c.put("organizerName", rs.getString("OrganizerName"));
                            sendJsonResponse(exchange, 200, c);
                            return;
                        }
                    }
                } else if (method.equals("GET")) {
                    JSONObject authUser = getAuthUser(exchange);
                    if (authUser == null) {
                        sendError(exchange, 401, "Unauthorized. Access Denied.");
                        return;
                    }
                    String[] parts = path.split("/");
                    int bookingId = Integer.parseInt(parts[3]);

                    String sql = "SELECT B.BookingID, B.UserID, B.AttendanceStatus, B.BookingDate, E.EventID, E.Title AS EventTitle, E.Category, E.Date AS EventDate, E.Time AS EventTime, E.Venue, U.Name AS AttendeeName, U.Email AS AttendeeEmail, Org.Name AS OrganizerName FROM Bookings B INNER JOIN Events E ON B.EventID = E.EventID INNER JOIN Users U ON B.UserID = U.UserID INNER JOIN Users Org ON E.OrganizerID = Org.UserID WHERE B.BookingID = ?";

                    try (Connection conn = DatabaseManager.getConnection()) {
                        String certHash = "";
                        String issuedAt = "";
                        String attendeeName = "", attendeeEmail = "", eventTitle = "", category = "", eventDate = "", venue = "", organizerName = "";

                        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                            stmt.setInt(1, bookingId);
                            try (ResultSet rs = stmt.executeQuery()) {
                                if (!rs.next()) {
                                    sendError(exchange, 404, "Booking record not found.");
                                    return;
                                }
                                String attStatus = rs.getString("AttendanceStatus");
                                boolean isCheckedIn = ("Checked-In".equals(attStatus) || "Checked In".equals(attStatus));
                                if (!isCheckedIn && !authUser.getString("Role").equals("Admin")) {
                                    sendError(exchange, 400, "Certificate unavailable. Gate check-in has not been marked yet.");
                                    return;
                                }
                                attendeeName = rs.getString("AttendeeName");
                                attendeeEmail = rs.getString("AttendeeEmail");
                                eventTitle = rs.getString("EventTitle");
                                category = rs.getString("Category");
                                eventDate = rs.getString("EventDate");
                                venue = rs.getString("Venue");
                                organizerName = rs.getString("OrganizerName");
                            }
                        }

                        try (PreparedStatement s2 = conn.prepareStatement("SELECT CertHash, IssuedAt FROM Certificates WHERE BookingID = ?")) {
                            s2.setInt(1, bookingId);
                            try (ResultSet rs2 = s2.executeQuery()) {
                                if (rs2.next()) {
                                    certHash = rs2.getString("CertHash");
                                    issuedAt = rs2.getString("IssuedAt");
                                }
                            }
                        }

                        if (certHash.isEmpty()) {
                            certHash = generateSha256("EVENTSPHERE-CERT-B" + bookingId + "-SALT99-" + System.currentTimeMillis());
                            try (PreparedStatement s3 = conn.prepareStatement("INSERT INTO Certificates (BookingID, CertHash) VALUES (?, ?)")) {
                                s3.setInt(1, bookingId);
                                s3.setString(2, certHash);
                                s3.executeUpdate();
                            }
                            issuedAt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
                        }

                        JSONObject res = new JSONObject();
                        res.put("certId", "CERT-ES-" + bookingId);
                        res.put("certHash", certHash);
                        res.put("issuedAt", issuedAt);
                        res.put("attendeeName", attendeeName);
                        res.put("attendeeEmail", attendeeEmail);
                        res.put("eventTitle", eventTitle);
                        res.put("category", category);
                        res.put("eventDate", eventDate);
                        res.put("venue", venue);
                        res.put("organizerName", organizerName);
                        sendJsonResponse(exchange, 200, res);
                    }
                    return;
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } catch (Exception e) {
                sendError(exchange, 500, "Certificate Error: " + e.getMessage());
            }
        }

        private String generateSha256(String input) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
                StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) hexString.append('0');
                    hexString.append(hex);
                }
                return hexString.toString();
            } catch (Exception e) {
                return "0000000000000000000000000000000000000000000000000000000000000000";
            }
        }
    }

    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }

            File publicDir = new File("public");
            File requestedFile = new File(publicDir, path);

            if (!requestedFile.exists() || requestedFile.isDirectory()) {
                requestedFile = new File(publicDir, "index.html");
            }

            String contentType = getContentType(requestedFile.getName());
            byte[] fileBytes = Files.readAllBytes(requestedFile.toPath());

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, fileBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fileBytes);
            }
        }

        private String getContentType(String filename) {
            if (filename.endsWith(".html")) return "text/html; charset=UTF-8";
            if (filename.endsWith(".css")) return "text/css; charset=UTF-8";
            if (filename.endsWith(".js")) return "application/javascript; charset=UTF-8";
            if (filename.endsWith(".png")) return "image/png";
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) return "image/jpeg";
            if (filename.endsWith(".svg")) return "image/svg+xml";
            if (filename.endsWith(".json")) return "application/json";
            return "text/plain; charset=UTF-8";
        }
    }
}
