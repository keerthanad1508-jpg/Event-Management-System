const express = require('express');
const mysql = require('mysql2/promise');
const cors = require('cors');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const path = require('path');
const fs = require('fs');
const os = require('os');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 5000;

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Database Connection Pool Configuration
const dbConfig = {
  host: process.env.DB_HOST || 'localhost',
  port: parseInt(process.env.DB_PORT || '3306'),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD !== undefined ? process.env.DB_PASSWORD : 'rootpassword',
  database: process.env.DB_NAME || 'event_management_db',
  waitForConnections: true,
  connectionLimit: 10,
  queueLimit: 0
};

// Global flags and connection holders
let poolInstance = null; // Holds MySQL pool
let isSqlite = false;
let sqliteDb = null;

// Mock Pool Router to handle MySQL or SQLite raw SQL transparently
const pool = {
  async query(sql, params = []) {
    if (isSqlite) {
      const cleanSql = sql.replace(/\s+FOR\s+UPDATE/gi, '');
      const trimmedSql = cleanSql.trim();
      const isSelect = trimmedSql.toUpperCase().startsWith('SELECT');
      try {
        const stmt = sqliteDb.prepare(cleanSql);
        if (isSelect) {
          const rows = stmt.all(...params);
          return [rows, null];
        } else {
          const info = stmt.run(...params);
          return [{ insertId: info.lastInsertRowid, affectedRows: info.changes }, null];
        }
      } catch (err) {
        throw err;
      }
    } else {
      return await poolInstance.query(sql, params);
    }
  },

  async getConnection() {
    if (isSqlite) {
      return {
        query: async (sql, params = []) => {
          const cleanSql = sql.replace(/\s+FOR\s+UPDATE/gi, '');
          const trimmedSql = cleanSql.trim();
          const isSelect = trimmedSql.toUpperCase().startsWith('SELECT');
          const stmt = sqliteDb.prepare(cleanSql);
          if (isSelect) {
            const rows = stmt.all(...params);
            return [rows, null];
          } else {
            const info = stmt.run(...params);
            return [{ insertId: info.lastInsertRowid, affectedRows: info.changes }, null];
          }
        },
        beginTransaction: async () => {
          sqliteDb.exec('BEGIN TRANSACTION');
        },
        commit: async () => {
          sqliteDb.exec('COMMIT');
        },
        rollback: async () => {
          sqliteDb.exec('ROLLBACK');
        },
        release: () => {}
      };
    } else {
      return await poolInstance.getConnection();
    }
  }
};

// Initialize SQLite fallback database
function initializeSqlite() {
  try {
    const dbPath = path.join(__dirname, 'event_management.db');
    const dbExists = fs.existsSync(dbPath);

    const { DatabaseSync } = require('node:sqlite');
    sqliteDb = new DatabaseSync(dbPath);
    isSqlite = true;
    
    // Performance Pragmas (WAL mode & RAM Cache)
    sqliteDb.exec('PRAGMA foreign_keys = ON;');
    try {
      sqliteDb.exec('PRAGMA journal_mode = WAL;');
      sqliteDb.exec('PRAGMA synchronous = NORMAL;');
      sqliteDb.exec('PRAGMA temp_store = MEMORY;');
      sqliteDb.exec('PRAGMA cache_size = -64000;');
    } catch (pErr) {}
    
    // Only seed database if it did not exist previously
    if (!dbExists) {
      const schemaPath = path.join(__dirname, 'schema.sql');
      if (fs.existsSync(schemaPath)) {
        let sql = fs.readFileSync(schemaPath, 'utf8');
        
        // Translate MySQL specific keywords to SQLite compatible SQL DDL
        sql = sql.replace(/INT AUTO_INCREMENT PRIMARY KEY/gi, 'INTEGER PRIMARY KEY AUTOINCREMENT');
        sql = sql.replace(/AUTO_INCREMENT/gi, 'AUTOINCREMENT');
        sql = sql.replace(/ENUM\([^)]+\)/gi, 'TEXT');
        sql = sql.replace(/UNIQUE KEY \w+ \(([^)]+)\)/gi, 'UNIQUE($1)');
        
        sqliteDb.exec(sql);
        console.log('==========================================================');
        console.log('SQLite database "event_management.db" initialized & pre-seeded!');
        console.log('==========================================================');
      }
    } else {
      console.log('==========================================================');
      console.log('SQLite database "event_management.db" loaded successfully.');
      console.log('==========================================================');
    }

    // Auto-migrate schema extensions & performance indexes if missing
    try {
      sqliteDb.exec(`
        CREATE TABLE IF NOT EXISTS Reviews (
          ReviewID INTEGER PRIMARY KEY AUTOINCREMENT,
          EventID INTEGER NOT NULL,
          UserID INTEGER NOT NULL,
          Rating INTEGER NOT NULL CHECK (Rating >= 1 AND Rating <= 5),
          Comment TEXT,
          CreatedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (EventID) REFERENCES Events(EventID) ON DELETE CASCADE,
          FOREIGN KEY (UserID) REFERENCES Users(UserID) ON DELETE CASCADE,
          UNIQUE(UserID, EventID)
        );
        CREATE TABLE IF NOT EXISTS UserInterests (
          InterestID INTEGER PRIMARY KEY AUTOINCREMENT,
          UserID INTEGER NOT NULL,
          InterestTag TEXT NOT NULL,
          FOREIGN KEY (UserID) REFERENCES Users(UserID) ON DELETE CASCADE,
          UNIQUE(UserID, InterestTag)
        );
        CREATE TABLE IF NOT EXISTS Certificates (
          CertID INTEGER PRIMARY KEY AUTOINCREMENT,
          BookingID INTEGER UNIQUE NOT NULL,
          CertHash TEXT UNIQUE NOT NULL,
          IssuedAt TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
          FOREIGN KEY (BookingID) REFERENCES Bookings(BookingID) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_events_date ON Events(Date, Time);
        CREATE INDEX IF NOT EXISTS idx_events_organizer ON Events(OrganizerID);
        CREATE INDEX IF NOT EXISTS idx_bookings_user ON Bookings(UserID);
        CREATE INDEX IF NOT EXISTS idx_bookings_event ON Bookings(EventID);
        CREATE INDEX IF NOT EXISTS idx_reviews_event ON Reviews(EventID);
        CREATE INDEX IF NOT EXISTS idx_notifications_user ON Notifications(UserID, IsRead);
        CREATE INDEX IF NOT EXISTS idx_user_interests ON UserInterests(UserID);
        CREATE INDEX IF NOT EXISTS idx_cert_hash ON Certificates(CertHash);
      `);
      
      const columns = sqliteDb.prepare("PRAGMA table_info(Bookings)").all();
      const hasAttendanceStatus = columns.some(c => c.name === 'AttendanceStatus');
      if (!hasAttendanceStatus) {
        sqliteDb.exec("ALTER TABLE Bookings ADD COLUMN AttendanceStatus TEXT NOT NULL DEFAULT 'Registered';");
      }

      const uCols = sqliteDb.prepare("PRAGMA table_info(Users)").all();
      if (!uCols.some(c => c.name === 'USN')) {
        sqliteDb.exec("ALTER TABLE Users ADD COLUMN USN TEXT DEFAULT '1MS21CS042';");
      }
      if (!uCols.some(c => c.name === 'Department')) {
        sqliteDb.exec("ALTER TABLE Users ADD COLUMN Department TEXT DEFAULT 'Computer Science & Engineering';");
      }
    } catch (migErr) {
      console.log('SQLite migration note:', migErr.message);
    }
  } catch (err) {
    console.error('Failed to initialize SQLite fallback database:', err.message);
  }
}

// Initialize database pool and verify connection
async function initDb() {
  try {
    poolInstance = mysql.createPool(dbConfig);
    // Test query
    const [rows] = await poolInstance.query('SELECT 1');
    console.log('Connected to MySQL Database pool successfully!');
  } catch (error) {
    console.log('==========================================================');
    console.log('MySQL Connection failed. Credentials might be wrong.');
    console.log('FALLING BACK: Booting up local SQLite relational database.');
    console.log('==========================================================');
    initializeSqlite();
  }
}

initDb();

// Helper middleware to verify User and Role (Session simulation)
async function authMiddleware(req, res, next) {
  const userId = req.headers['x-user-id'];
  if (!userId) {
    return res.status(401).json({ error: 'Unauthorized. Access Denied.' });
  }
  
  try {
    const [users] = await pool.query('SELECT UserID, Name, Email, Role FROM Users WHERE UserID = ?', [userId]);
    if (users.length === 0) {
      return res.status(401).json({ error: 'User session invalid. Please log in again.' });
    }
    req.user = users[0];
    next();
  } catch (err) {
    return res.status(500).json({ error: 'Server authentication error.' });
  }
}

// Check role middleware helper
function checkRole(roles) {
  return (req, res, next) => {
    if (!roles.includes(req.user.Role)) {
      return res.status(403).json({ error: `Access forbidden for role: ${req.user.Role}` });
    }
    next();
  };
}

// Notification Helpers
async function createNotification(conn, userId, message) {
  try {
    await conn.query('INSERT INTO Notifications (UserID, Message, IsRead) VALUES (?, ?, 0)', [userId, message]);
  } catch (err) {
    console.error('Failed to create notification:', err);
  }
}

async function notifyAttendeesOfEvent(conn, eventId, message) {
  try {
    const [bookings] = await conn.query(
      "SELECT DISTINCT UserID FROM Bookings WHERE EventID = ? AND Status = 'Confirmed'",
      [eventId]
    );
    for (const b of bookings) {
      await createNotification(conn, b.UserID, message);
    }
  } catch (err) {
    console.error('Failed to notify event attendees:', err);
  }
}

async function notifyAllAttendees(conn, message) {
  try {
    const [attendees] = await conn.query("SELECT UserID FROM Users WHERE Role = 'Attendee'");
    for (const a of attendees) {
      await createNotification(conn, a.UserID, message);
    }
  } catch (err) {
    console.error('Failed to notify all attendees:', err);
  }
}

// Smart local network IPv4 address detector (Filters virtual/hotspot and prioritizes Wi-Fi/Ethernet)
function getLocalIpAddress() {
  // 0. Check if a manual override is configured in the environment variables
  if (process.env.SERVER_IP && process.env.SERVER_IP.trim() !== '') {
    return process.env.SERVER_IP.trim();
  }

  const interfaces = os.networkInterfaces();
  
  // 1. Prioritize Wi-Fi and Ethernet adapters
  for (const name of Object.keys(interfaces)) {
    const lowerName = name.toLowerCase();
    if (lowerName.includes('wi-fi') || lowerName.includes('wifi') || lowerName.includes('ethernet')) {
      for (const iface of interfaces[name]) {
        if (iface.family === 'IPv4' && !iface.internal) {
          return iface.address;
        }
      }
    }
  }
  
  // 2. Fallback to any other non-internal interface
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

// ==========================================================
// CONFIG ENDPOINTS (Helper for QR and audio context)
// ==========================================================
app.get('/api/config', (req, res) => {
  const localIp = getLocalIpAddress();
  res.json({
    localIp,
    port: PORT,
    localUrl: `http://${localIp}:${PORT}`
  });
});

// ==========================================================
// 1. AUTHENTICATION ENDPOINTS
// ==========================================================

// Register Route
app.post('/api/auth/register', async (req, res) => {
  const { name, email, password, role, usn, department } = req.body;
  
  if (!name || !email || !password || !role) {
    return res.status(400).json({ error: 'All fields (name, email, password, role) are required.' });
  }

  if (!['Admin', 'Organizer', 'Attendee'].includes(role)) {
    return res.status(400).json({ error: 'Invalid role selection.' });
  }

  const finalUsn = usn || '1MS21CS042';
  const finalDept = department || 'Computer Science & Engineering';

  try {
    const salt = await bcrypt.genSalt(10);
    const hashedPassword = await bcrypt.hash(password, salt);

    const [result] = await pool.query(
      'INSERT INTO Users (Name, Email, Password, Role, USN, Department) VALUES (?, ?, ?, ?, ?, ?)',
      [name, email, hashedPassword, role, finalUsn, finalDept]
    );

    res.status(201).json({
      message: 'Registration successful!',
      userId: result.insertId,
      user: { id: result.insertId, name, email, role, usn: finalUsn, department: finalDept }
    });
  } catch (err) {
    if (err.code === 'ER_DUP_ENTRY' || (err.message && err.message.includes('UNIQUE constraint failed'))) {
      return res.status(400).json({ error: 'Email already registered.' });
    }
    console.error('Registration Error:', err);
    res.status(500).json({ error: 'Registration failed.' });
  }
});

// Login Route
app.post('/api/auth/login', async (req, res) => {
  const { email, password } = req.body;

  if (!email || !password) {
    return res.status(400).json({ error: 'Email and password are required.' });
  }

  try {
    const [users] = await pool.query('SELECT * FROM Users WHERE Email = ?', [email]);
    if (users.length === 0) {
      return res.status(400).json({ error: 'Invalid Email or Password.' });
    }

    const user = users[0];
    
    let isMatch = false;
    if (user.Password.startsWith('$2a$') || user.Password.startsWith('$2b$')) {
      isMatch = await bcrypt.compare(password, user.Password);
    } else {
      isMatch = (password === user.Password);
    }

    if (!isMatch) {
      return res.status(400).json({ error: 'Invalid Email or Password.' });
    }

    res.json({
      message: 'Login successful!',
      user: {
        id: user.UserID,
        name: user.Name,
        email: user.Email,
        role: user.Role,
        usn: user.USN || '1MS21CS042',
        department: user.Department || 'Computer Science & Engineering'
      }
    });
  } catch (err) {
    console.error('Login Error:', err);
    res.status(500).json({ error: 'Server login error.' });
  }
});

// Get User Profile Credentials & Skills
app.get('/api/users/profile', authMiddleware, async (req, res) => {
  const userId = req.user.UserID;
  try {
    const [users] = await pool.query('SELECT UserID, Name, Email, Role, USN, Department FROM Users WHERE UserID = ?', [userId]);
    if (users.length === 0) return res.status(404).json({ error: 'User profile not found.' });

    const u = users[0];
    const [tagsRows] = await pool.query('SELECT InterestTag FROM UserInterests WHERE UserID = ?', [userId]);
    const interestTags = tagsRows.map(r => r.InterestTag);

    res.json({
      id: u.UserID,
      name: u.Name,
      email: u.Email,
      role: u.Role,
      usn: u.USN || '1MS21CS042',
      department: u.Department || 'Computer Science & Engineering',
      interestTags
    });
  } catch (err) {
    console.error('Fetch Profile Error:', err);
    res.status(500).json({ error: 'Failed to retrieve profile.' });
  }
});

// Update Student Skill & Interest Profile
app.post('/api/users/profile/interests', authMiddleware, async (req, res) => {
  const userId = req.user.UserID;
  const { tags } = req.body;

  if (!Array.isArray(tags)) {
    return res.status(400).json({ error: 'Tags must be an array of strings.' });
  }

  try {
    await pool.query('DELETE FROM UserInterests WHERE UserID = ?', [userId]);
    for (const tag of tags) {
      const cleanTag = tag.trim();
      if (cleanTag) {
        await pool.query('INSERT INTO UserInterests (UserID, InterestTag) VALUES (?, ?)', [userId, cleanTag]);
      }
    }

    res.json({ message: 'Skill & Interest Profile updated successfully!', tags });
  } catch (err) {
    console.error('Update Interests Error:', err);
    res.status(500).json({ error: 'Failed to update interest profile.' });
  }
});

// ==========================================================
// 2. EVENT ENDPOINTS (ORGANIZER CRUD & ATTENDEE READ)
// ==========================================================

// Get All Events
app.get('/api/events', async (req, res) => {
  try {
    const [events] = await pool.query(
      `SELECT E.*, U.Name AS OrganizerName 
       FROM Events E 
       INNER JOIN Users U ON E.OrganizerID = U.UserID 
       ORDER BY E.Date ASC, E.Time ASC`
    );
    res.json(events);
  } catch (err) {
    console.error('Fetch Events Error:', err);
    res.status(500).json({ error: 'Failed to retrieve events.' });
  }
});

// Create Event (Organizer & Admin Only)
app.post('/api/events', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const { title, category, description, date, time, venue, totalSlots, price, imageUrl } = req.body;
  const organizerId = req.user.UserID;

  if (!title || !date || !time || !venue || totalSlots === undefined || price === undefined) {
    return res.status(400).json({ error: 'All fields (title, date, time, venue, totalSlots, price) are required.' });
  }

  if (parseInt(totalSlots) <= 0) {
    return res.status(400).json({ error: 'Total slots must be greater than zero.' });
  }

  try {
    const [result] = await pool.query(
      `INSERT INTO Events (Title, Category, Description, Date, Time, Venue, TotalSlots, AvailableSlots, Price, OrganizerID, ImageURL) 
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [title, category || 'Academic', description || '', date, time, venue, totalSlots, totalSlots, price, organizerId, imageUrl || null]
    );

    // Notify all student attendees about the new event organized
    await notifyAllAttendees(
      pool,
      `A new event '${title}' (${category || 'Academic'}) has been organized at ${venue} on ${new Date(date).toLocaleDateString()} at ${time.substring(0, 5)}! Book your tickets today.`
    );

    res.status(201).json({
      message: 'Event created successfully!',
      event: {
        id: result.insertId,
        title,
        description,
        date,
        time,
        venue,
        totalSlots,
        availableSlots: totalSlots,
        price,
        organizerId,
        imageUrl: imageUrl || null
      }
    });
  } catch (err) {
    console.error('Create Event Error:', err);
    res.status(500).json({ error: 'Failed to create event.' });
  }
});

// Update Event (Organizer & Admin Only)
app.put('/api/events/:id', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const eventId = req.params.id;
  const { title, category, description, date, time, venue, totalSlots, price, imageUrl } = req.body;
  const userId = req.user.UserID;
  const userRole = req.user.Role;

  if (!title || !date || !time || !venue || totalSlots === undefined || price === undefined) {
    return res.status(400).json({ error: 'All fields are required.' });
  }

  const conn = await pool.getConnection();
  try {
    await conn.beginTransaction();

    const [events] = await conn.query('SELECT * FROM Events WHERE EventID = ? FOR UPDATE', [eventId]);
    if (events.length === 0) {
      await conn.rollback();
      return res.status(404).json({ error: 'Event not found.' });
    }

    const event = events[0];
    
    if (event.OrganizerID !== userId && userRole !== 'Admin') {
      await conn.rollback();
      return res.status(403).json({ error: 'Access denied. You do not own this event.' });
    }

    const [bookingsCount] = await conn.query(
      "SELECT COUNT(*) AS count FROM Bookings WHERE EventID = ? AND Status = 'Confirmed'",
      [eventId]
    );
    const bookedSlots = bookingsCount[0].count;

    if (totalSlots < bookedSlots) {
      await conn.rollback();
      return res.status(400).json({ 
        error: `Cannot reduce total slots to ${totalSlots}. There are already ${bookedSlots} confirmed bookings for this event.` 
      });
    }

    const newAvailableSlots = totalSlots - bookedSlots;
    const previousAvailableSlots = event.AvailableSlots;

    await conn.query(
      `UPDATE Events 
       SET Title = ?, Category = ?, Description = ?, Date = ?, Time = ?, Venue = ?, TotalSlots = ?, AvailableSlots = ?, Price = ?, ImageURL = ? 
       WHERE EventID = ?`,
      [title, category || 'Academic', description, date, time, venue, totalSlots, newAvailableSlots, price, imageUrl || null, eventId]
    );

    await notifyAttendeesOfEvent(
      conn,
      eventId,
      `The event details for '${title}' have been updated by the organizer.`
    );

    if (previousAvailableSlots <= 0 && newAvailableSlots > 0) {
      await notifyAllAttendees(
        conn,
        `Tickets are now available for '${title}'! Book your seats today.`
      );
    }

    await conn.commit();
    res.json({ message: 'Event updated successfully!', availableSlots: newAvailableSlots });
  } catch (err) {
    await conn.rollback();
    console.error('Update Event Error:', err);
    res.status(500).json({ error: 'Failed to update event.' });
  } finally {
    conn.release();
  }
});

// Delete Event (Organizer & Admin Only)
app.delete('/api/events/:id', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const eventId = req.params.id;
  const userId = req.user.UserID;
  const userRole = req.user.Role;

  try {
    const [events] = await pool.query('SELECT OrganizerID FROM Events WHERE EventID = ?', [eventId]);
    if (events.length === 0) {
      return res.status(404).json({ error: 'Event not found.' });
    }

    if (events[0].OrganizerID !== userId && userRole !== 'Admin') {
      return res.status(403).json({ error: 'Access denied. You do not own this event.' });
    }

    await pool.query('DELETE FROM Events WHERE EventID = ?', [eventId]);
    res.json({ message: 'Event and all associated bookings deleted successfully.' });
  } catch (err) {
    console.error('Delete Event Error:', err);
    res.status(500).json({ error: 'Failed to delete event.' });
  }
});

// ==========================================================
// 3. BOOKINGS ENDPOINTS (TRANSACTIONS & INNER JOINS)
// ==========================================================

// Book Ticket: Raw SQL Transaction
app.post('/api/bookings', authMiddleware, checkRole(['Attendee', 'Admin']), async (req, res) => {
  const { eventId, paymentMethod, transactionId } = req.body;
  const userId = req.user.UserID;

  if (!eventId) {
    return res.status(400).json({ error: 'Event ID is required.' });
  }

  const conn = await pool.getConnection();

  try {
    await conn.beginTransaction();

    const [events] = await conn.query(
      'SELECT Title, AvailableSlots, TotalSlots, Price, OrganizerID FROM Events WHERE EventID = ? FOR UPDATE',
      [eventId]
    );

    if (events.length === 0) {
      await conn.rollback();
      return res.status(404).json({ error: 'Event not found.' });
    }

    const event = events[0];

    if (event.AvailableSlots <= 0) {
      await conn.rollback();
      return res.status(400).json({ error: 'This event is fully booked! No slots available.' });
    }

    const [existingBookings] = await conn.query(
      'SELECT BookingID, Status FROM Bookings WHERE UserID = ? AND EventID = ?',
      [userId, eventId]
    );

    let bookingId;

    if (existingBookings.length > 0) {
      const booking = existingBookings[0];
      if (booking.Status === 'Confirmed') {
        await conn.rollback();
        return res.status(400).json({ error: 'You have already booked a ticket for this event.' });
      } else {
        await conn.query(
          "UPDATE Bookings SET Status = 'Confirmed', BookingDate = CURRENT_TIMESTAMP WHERE BookingID = ?",
          [booking.BookingID]
        );
        bookingId = booking.BookingID;
      }
    } else {
      const [insertResult] = await conn.query(
        "INSERT INTO Bookings (UserID, EventID, Status) VALUES (?, ?, 'Confirmed')",
        [userId, eventId]
      );
      bookingId = insertResult.insertId;
    }

    await conn.query(
      'UPDATE Events SET AvailableSlots = AvailableSlots - 1 WHERE EventID = ?',
      [eventId]
    );

    const amountPaid = parseFloat(event.Price);
    const payMethod = paymentMethod || (amountPaid > 0 ? 'UPI' : 'Free');
    const txnId = transactionId || `TXN-${payMethod.toUpperCase()}-${Date.now()}-${Math.floor(1000 + Math.random() * 9000)}`;

    await conn.query(
      "INSERT INTO Payments (BookingID, Amount, PaymentMethod, TransactionID, Status) VALUES (?, ?, ?, ?, 'Paid')",
      [bookingId, amountPaid, payMethod, txnId]
    );

    await createNotification(
      conn,
      userId,
      `Your booking for '${event.Title}' has been confirmed! Price paid: ₹${amountPaid.toFixed(2)}.`
    );

    await createNotification(
      conn,
      event.OrganizerID,
      `Attendee '${req.user.Name}' has successfully booked a ticket for your event '${event.Title}'.`
    );

    const [admins] = await conn.query("SELECT UserID FROM Users WHERE Role = 'Admin'");
    for (const admin of admins) {
      await createNotification(
        conn,
        admin.UserID,
        `Attendee '${req.user.Name}' has booked a ticket for event '${event.Title}' (Organizer ID: ${event.OrganizerID}).`
      );
    }

    await conn.commit();
    res.status(201).json({ message: 'Ticket booked successfully! Booking confirmed.' });

  } catch (err) {
    await conn.rollback();
    console.error('Booking Transaction Error:', err);
    res.status(500).json({ error: 'Booking failed. Transaction rolled back.' });
  } finally {
    conn.release();
  }
});

// View My Bookings (Attendee, Organizer & Admin)
app.get('/api/bookings/my', authMiddleware, checkRole(['Attendee', 'Organizer', 'Admin']), async (req, res) => {
  const userId = req.user.UserID;

  try {
    const [bookings] = await pool.query(
      `SELECT B.BookingID, B.BookingDate, B.Status, E.EventID, E.Title, E.Date, E.Time, E.Venue, E.Price, U.Name AS OrganizerName
       FROM Bookings B
       INNER JOIN Events E ON B.EventID = E.EventID
       INNER JOIN Users U ON E.OrganizerID = U.UserID
       WHERE B.UserID = ?
       ORDER BY B.BookingDate DESC`,
      [userId]
    );
    res.json(bookings);
  } catch (err) {
    console.error('Fetch My Bookings Error:', err);
    res.status(500).json({ error: 'Failed to retrieve bookings.' });
  }
});

// Cancel Booking
app.post('/api/bookings/cancel', authMiddleware, checkRole(['Attendee', 'Admin']), async (req, res) => {
  const { bookingId } = req.body;
  const userId = req.user.UserID;

  if (!bookingId) {
    return res.status(400).json({ error: 'Booking ID is required.' });
  }

  const conn = await pool.getConnection();

  try {
    await conn.beginTransaction();

    const [bookings] = await conn.query(
      'SELECT EventID, Status FROM Bookings WHERE BookingID = ? AND UserID = ? FOR UPDATE',
      [bookingId, userId]
    );

    if (bookings.length === 0) {
      await conn.rollback();
      return res.status(404).json({ error: 'Booking not found or not owned by you.' });
    }

    const booking = bookings[0];

    if (booking.Status === 'Cancelled') {
      await conn.rollback();
      return res.status(400).json({ error: 'This booking is already cancelled.' });
    }

    await conn.query(
      "UPDATE Bookings SET Status = 'Cancelled' WHERE BookingID = ?",
      [bookingId]
    );

    await conn.query(
      'UPDATE Events SET AvailableSlots = AvailableSlots + 1 WHERE EventID = ?',
      [booking.EventID]
    );

    await conn.query(
      "UPDATE Payments SET Status = 'Refunded' WHERE BookingID = ?",
      [bookingId]
    );

    const [events] = await conn.query(
      'SELECT Title, AvailableSlots FROM Events WHERE EventID = ?',
      [booking.EventID]
    );
    const event = events[0];

    const [payments] = await conn.query(
      'SELECT Amount FROM Payments WHERE BookingID = ?',
      [bookingId]
    );
    const refundAmount = payments.length > 0 ? parseFloat(payments[0].Amount) : 0;

    await createNotification(
      conn,
      userId,
      `Your booking for '${event.Title}' has been cancelled. A refund of ₹${refundAmount.toFixed(2)} has been initiated.`
    );

    if (event.AvailableSlots === 0) {
      await notifyAllAttendees(
        conn,
        `Tickets are now available for '${event.Title}'! Book your seats today.`
      );
    }

    await conn.commit();
    res.json({ message: 'Booking cancelled successfully. Ticket released.' });

  } catch (err) {
    await conn.rollback();
    console.error('Cancel Booking Transaction Error:', err);
    res.status(500).json({ error: 'Cancellation failed. Transaction rolled back.' });
  } finally {
    conn.release();
  }
});

// GET Public Ticket Verification (Scanned QR code)
app.get('/api/bookings/verify/:bookingId', async (req, res) => {
  const bookingId = req.params.bookingId;
  try {
    const [bookings] = await pool.query(
      `SELECT B.BookingID, B.Status AS BookingStatus, B.BookingDate,
              E.Title AS EventTitle, E.Date AS EventDate, E.Time AS EventTime, E.Venue, E.Price, E.Category,
              U.Name AS AttendeeName, U.Email AS AttendeeEmail
       FROM Bookings B
       INNER JOIN Events E ON B.EventID = E.EventID
       INNER JOIN Users U ON B.UserID = U.UserID
       WHERE B.BookingID = ?`,
      [bookingId]
    );

    if (bookings.length === 0) {
      return res.status(404).json({ error: 'Ticket reference code is invalid or does not exist.' });
    }

    res.json(bookings[0]);
  } catch (err) {
    console.error('Verify Booking Error:', err);
    res.status(500).json({ error: 'Failed to verify ticket.' });
  }
});

// ==========================================================
// 4. ADMIN DASHBOARD REPORT (METRICS ENDPOINT)
// ==========================================================
app.get('/api/admin/stats', authMiddleware, checkRole(['Admin', 'Organizer', 'Attendee']), async (req, res) => {
  const userId = req.user.UserID;
  const userRole = req.user.Role;

  try {
    let usersCount = { count: 0 }, eventsCount = { count: 0 }, bookingsCount = { count: 0 };
    let roleStats = [];
    let eventPopularity = [];
    let detailedReports = [];
    let totalRevenue = 0;

    if (userRole === 'Admin') {
      const [uCount] = await pool.query('SELECT COUNT(*) AS count FROM Users');
      usersCount = uCount[0] || { count: 0 };
      const [eCount] = await pool.query('SELECT COUNT(*) AS count FROM Events');
      eventsCount = eCount[0] || { count: 0 };
      const [bCount] = await pool.query("SELECT COUNT(*) AS count FROM Bookings WHERE Status = 'Confirmed'");
      bookingsCount = bCount[0] || { count: 0 };
      
      [roleStats] = await pool.query('SELECT Role, COUNT(*) AS count FROM Users GROUP BY Role');
      
      [eventPopularity] = await pool.query(
        `SELECT E.Title, COUNT(B.BookingID) AS BookingsCount 
         FROM Events E 
         LEFT JOIN Bookings B ON E.EventID = B.EventID AND B.Status = 'Confirmed'
         GROUP BY E.EventID 
         ORDER BY BookingsCount DESC 
         LIMIT 5`
      );

      const [revResult] = await pool.query(
        "SELECT SUM(Amount) AS total FROM Payments WHERE Status = 'Paid'"
      );
      totalRevenue = revResult[0] ? (revResult[0].total || 0) : 0;

      [detailedReports] = await pool.query(
        `SELECT E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots,
                COUNT(CASE WHEN B.Status = 'Confirmed' THEN 1 END) AS BookingsCount,
                COALESCE(SUM(CASE WHEN B.Status = 'Confirmed' AND P.Status = 'Paid' THEN P.Amount ELSE 0 END), 0) AS Revenue
         FROM Events E
         LEFT JOIN Bookings B ON E.EventID = B.EventID
         LEFT JOIN Payments P ON B.BookingID = P.BookingID
         GROUP BY E.EventID
         ORDER BY BookingsCount DESC`
      );

    } else if (userRole === 'Organizer') {
      const [uCount] = await pool.query(
        "SELECT COUNT(DISTINCT B.UserID) AS count FROM Bookings B INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND B.Status = 'Confirmed'",
        [userId]
      );
      usersCount = uCount[0] || { count: 0 };
      const [eCount] = await pool.query('SELECT COUNT(*) AS count FROM Events WHERE OrganizerID = ?', [userId]);
      eventsCount = eCount[0] || { count: 0 };
      const [bCount] = await pool.query(
        "SELECT COUNT(*) AS count FROM Bookings B INNER JOIN Events E ON B.EventID = E.EventID WHERE E.OrganizerID = ? AND B.Status = 'Confirmed'",
        [userId]
      );
      bookingsCount = bCount[0] || { count: 0 };
      
      [roleStats] = await pool.query(
        `SELECT U.Role, COUNT(DISTINCT U.UserID) AS count
         FROM Users U
         INNER JOIN Bookings B ON U.UserID = B.UserID
         INNER JOIN Events E ON B.EventID = E.EventID
         WHERE E.OrganizerID = ? AND B.Status = 'Confirmed'
         GROUP BY U.Role`, [userId]
      );

      [eventPopularity] = await pool.query(
        `SELECT E.Title, COUNT(B.BookingID) AS BookingsCount 
         FROM Events E 
         LEFT JOIN Bookings B ON E.EventID = B.EventID AND B.Status = 'Confirmed'
         WHERE E.OrganizerID = ?
         GROUP BY E.EventID 
         ORDER BY BookingsCount DESC 
         LIMIT 5`, [userId]
      );

      const [revResult] = await pool.query(
        `SELECT SUM(P.Amount) AS total 
         FROM Payments P
         INNER JOIN Bookings B ON P.BookingID = B.BookingID
         INNER JOIN Events E ON B.EventID = E.EventID
         WHERE E.OrganizerID = ? AND P.Status = 'Paid'`, [userId]
      );
      totalRevenue = revResult[0] ? (revResult[0].total || 0) : 0;

      [detailedReports] = await pool.query(
        `SELECT E.EventID, E.Title, E.Price, E.TotalSlots, E.AvailableSlots,
                COUNT(CASE WHEN B.Status = 'Confirmed' THEN 1 END) AS BookingsCount,
                COALESCE(SUM(CASE WHEN B.Status = 'Confirmed' AND P.Status = 'Paid' THEN P.Amount ELSE 0 END), 0) AS Revenue
         FROM Events E
         LEFT JOIN Bookings B ON E.EventID = B.EventID
         LEFT JOIN Payments P ON B.BookingID = P.BookingID
         WHERE E.OrganizerID = ?
         GROUP BY E.EventID
         ORDER BY BookingsCount DESC`, [userId]
      );

    } else { // Attendee
      const [uCount] = await pool.query('SELECT COUNT(*) AS count FROM Users');
      usersCount = uCount[0] || { count: 0 };
      const [eCount] = await pool.query('SELECT COUNT(*) AS count FROM Events');
      eventsCount = eCount[0] || { count: 0 };
      const [bCount] = await pool.query("SELECT COUNT(*) AS count FROM Bookings WHERE UserID = ? AND Status = 'Confirmed'", [userId]);
      bookingsCount = bCount[0] || { count: 0 };
      
      [roleStats] = await pool.query('SELECT Role, COUNT(*) AS count FROM Users GROUP BY Role');
      
      [eventPopularity] = await pool.query(
        `SELECT E.Title, COUNT(B.BookingID) AS BookingsCount 
         FROM Events E 
         LEFT JOIN Bookings B ON E.EventID = B.EventID AND B.Status = 'Confirmed'
         GROUP BY E.EventID 
         ORDER BY BookingsCount DESC 
         LIMIT 5`
      );

      const [revResult] = await pool.query(
        "SELECT SUM(P.Amount) AS total FROM Payments P INNER JOIN Bookings B ON P.BookingID = B.BookingID WHERE B.UserID = ? AND P.Status = 'Paid'", [userId]
      );
      totalRevenue = revResult[0] ? (revResult[0].total || 0) : 0;
      
      detailedReports = [];
    }

    res.json({
      summary: {
        totalUsers: usersCount.count,
        totalEvents: eventsCount.count,
        activeBookings: bookingsCount.count,
        totalRevenue: totalRevenue
      },
      roleStats,
      eventPopularity,
      detailedReports
    });
  } catch (err) {
    console.error('Admin Stats Error:', err);
    res.status(500).json({ error: 'Failed to retrieve administrative metrics.' });
  }
});

// GET Bookings for specific Event (Organizer and Admin only)
app.get('/api/events/:id/bookings', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const eventId = req.params.id;
  const userId = req.user.UserID;
  const userRole = req.user.Role;

  try {
    const [events] = await pool.query('SELECT OrganizerID FROM Events WHERE EventID = ?', [eventId]);
    if (events.length === 0) {
      return res.status(404).json({ error: 'Event not found.' });
    }
    if (events[0].OrganizerID !== userId && userRole !== 'Admin') {
      return res.status(403).json({ error: 'Access denied. You do not own this event.' });
    }

    const [bookings] = await pool.query(
      `SELECT B.BookingID, B.BookingDate, B.Status, U.Name AS AttendeeName, U.Email AS AttendeeEmail,
              COALESCE(P.Amount, 0) AS PaidAmount, COALESCE(P.PaymentMethod, 'N/A') AS PaymentMethod,
              COALESCE(P.TransactionID, 'N/A') AS TransactionID, COALESCE(P.Status, 'N/A') AS PaymentStatus
       FROM Bookings B
       INNER JOIN Users U ON B.UserID = U.UserID
       LEFT JOIN Payments P ON B.BookingID = P.BookingID
       WHERE B.EventID = ?
       ORDER BY B.BookingDate DESC`,
      [eventId]
    );

    res.json(bookings);
  } catch (err) {
    console.error('Fetch Event Bookings Error:', err);
    res.status(500).json({ error: 'Failed to retrieve event bookings.' });
  }
});

// GET Notifications for current User
app.get('/api/notifications', authMiddleware, async (req, res) => {
  const userId = req.user.UserID;
  try {
    const [notifications] = await pool.query(
      'SELECT * FROM Notifications WHERE UserID = ? ORDER BY CreatedAt DESC LIMIT 50',
      [userId]
    );
    res.json(notifications);
  } catch (err) {
    console.error('Fetch Notifications Error:', err);
    res.status(500).json({ error: 'Failed to retrieve notifications.' });
  }
});

// POST Mark Notifications as Read
app.post('/api/notifications/read', authMiddleware, async (req, res) => {
  const userId = req.user.UserID;
  try {
    await pool.query(
      'UPDATE Notifications SET IsRead = 1 WHERE UserID = ?',
      [userId]
    );
    res.json({ message: 'Notifications marked as read.' });
  } catch (err) {
    console.error('Mark Notifications Read Error:', err);
    res.status(500).json({ error: 'Failed to update notifications.' });
  }
});

// ==========================================================
// 5. REVIEWS & ATTENDANCE ENDPOINTS
// ==========================================================

// Get Reviews for Event
app.get('/api/events/:id/reviews', async (req, res) => {
  const eventId = req.params.id;
  try {
    const [reviews] = await pool.query(
      `SELECT R.ReviewID, R.Rating, R.Comment, R.CreatedAt, U.Name AS ReviewerName, U.Role AS ReviewerRole
       FROM Reviews R
       INNER JOIN Users U ON R.UserID = U.UserID
       WHERE R.EventID = ?
       ORDER BY R.CreatedAt DESC`,
      [eventId]
    );

    const [stats] = await pool.query(
      `SELECT AVG(Rating) AS avgRating, COUNT(*) AS reviewCount FROM Reviews WHERE EventID = ?`,
      [eventId]
    );

    res.json({
      reviews,
      avgRating: stats[0] && stats[0].avgRating ? parseFloat(stats[0].avgRating).toFixed(1) : 0,
      reviewCount: stats[0] ? stats[0].reviewCount : 0
    });
  } catch (err) {
    console.error('Fetch Reviews Error:', err);
    res.status(500).json({ error: 'Failed to retrieve event reviews.' });
  }
});

// Post Review for Event (Attendee or Admin with confirmed booking)
app.post('/api/events/:id/reviews', authMiddleware, async (req, res) => {
  const eventId = req.params.id;
  const userId = req.user.UserID;
  const { rating, comment } = req.body;

  if (!rating || rating < 1 || rating > 5) {
    return res.status(400).json({ error: 'Rating must be between 1 and 5 stars.' });
  }

  try {
    // Verify user has confirmed booking
    const [bookings] = await pool.query(
      "SELECT BookingID FROM Bookings WHERE UserID = ? AND EventID = ? AND Status = 'Confirmed'",
      [userId, eventId]
    );

    if (bookings.length === 0 && req.user.Role !== 'Admin') {
      return res.status(403).json({ error: 'Only attendees with a confirmed booking can review this event.' });
    }

    // Check if user already reviewed this event
    const [existing] = await pool.query(
      'SELECT ReviewID FROM Reviews WHERE UserID = ? AND EventID = ?',
      [userId, eventId]
    );

    if (existing.length > 0) {
      await pool.query(
        'UPDATE Reviews SET Rating = ?, Comment = ?, CreatedAt = CURRENT_TIMESTAMP WHERE ReviewID = ?',
        [rating, comment || '', existing[0].ReviewID]
      );
      return res.json({ message: 'Your review has been updated successfully!' });
    } else {
      await pool.query(
        'INSERT INTO Reviews (EventID, UserID, Rating, Comment) VALUES (?, ?, ?, ?)',
        [eventId, userId, rating, comment || '']
      );
      return res.status(201).json({ message: 'Thank you! Your review has been submitted.' });
    }
  } catch (err) {
    console.error('Submit Review Error:', err);
    res.status(500).json({ error: 'Failed to submit event review.' });
  }
});

// Get Attendee Roster for an Event (Organizer & Admin)
app.get('/api/events/:id/attendees', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const eventId = req.params.id;
  const userId = req.user.UserID;
  const userRole = req.user.Role;

  try {
    const [events] = await pool.query('SELECT OrganizerID, Title FROM Events WHERE EventID = ?', [eventId]);
    if (events.length === 0) {
      return res.status(404).json({ error: 'Event not found.' });
    }
    if (events[0].OrganizerID !== userId && userRole !== 'Admin') {
      return res.status(403).json({ error: 'Access denied. You do not own this event.' });
    }

    const [attendees] = await pool.query(
      `SELECT B.BookingID, B.BookingDate, B.Status, B.AttendanceStatus, U.UserID, U.Name, U.Email, U.Role,
              COALESCE(P.PaymentMethod, 'N/A') AS PaymentMethod, COALESCE(P.TransactionID, 'N/A') AS TransactionID,
              COALESCE(P.Amount, 0) AS AmountPaid
       FROM Bookings B
       INNER JOIN Users U ON B.UserID = U.UserID
       LEFT JOIN Payments P ON B.BookingID = P.BookingID
       WHERE B.EventID = ? AND B.Status = 'Confirmed'
       ORDER BY U.Name ASC`,
      [eventId]
    );

    res.json({
      eventTitle: events[0].Title,
      attendees
    });
  } catch (err) {
    console.error('Fetch Attendees Error:', err);
    res.status(500).json({ error: 'Failed to retrieve attendee roster.' });
  }
});

// Update Attendee Check-In Status (Organizer & Admin)
app.put('/api/bookings/:id/checkin', authMiddleware, checkRole(['Organizer', 'Admin']), async (req, res) => {
  const bookingId = req.params.id;
  const { status } = req.body; // 'Checked-In' or 'Registered'
  const newStatus = (status === 'Checked-In' || status === 'Checked In') ? 'Checked-In' : 'Registered';

  try {
    await pool.query(
      'UPDATE Bookings SET AttendanceStatus = ? WHERE BookingID = ?',
      [newStatus, bookingId]
    );
    res.json({ message: `Attendance updated to ${newStatus}`, status: newStatus });
  } catch (err) {
    console.error('Check-in Update Error:', err);
    res.status(500).json({ error: 'Failed to update attendance status.' });
  }
});

// ==========================================================
// 6. AI MATCHMAKER & CERTIFICATE ENDPOINTS
// ==========================================================

// GET AI Teammate Matchmaker recommendations for an Event
app.get('/api/events/:id/matchmaker', authMiddleware, async (req, res) => {
  const eventId = req.params.id;
  const currentUserId = req.user.UserID;

  try {
    const [myTagsRows] = await pool.query('SELECT InterestTag FROM UserInterests WHERE UserID = ?', [currentUserId]);
    const myTags = new Set(myTagsRows.map(r => r.InterestTag));

    if (myTags.size === 0) {
      myTags.add('Python');
      myTags.add('Algorithms');
    }

    const [attendees] = await pool.query(
      `SELECT DISTINCT U.UserID, U.Name, U.Email, U.Role
       FROM Bookings B
       INNER JOIN Users U ON B.UserID = U.UserID
       WHERE B.EventID = ? AND B.Status = 'Confirmed' AND U.UserID != ?`,
      [eventId, currentUserId]
    );

    const matches = [];

    for (const att of attendees) {
      const [attTagsRows] = await pool.query('SELECT InterestTag FROM UserInterests WHERE UserID = ?', [att.UserID]);
      const attTags = attTagsRows.map(r => r.InterestTag);

      let sharedCount = 0;
      attTags.forEach(tag => {
        if (myTags.has(tag)) sharedCount++;
      });

      const totalUnique = new Set([...myTags, ...attTags]).size;
      let jaccardScore = totalUnique > 0 ? (sharedCount / totalUnique) : 0;
      
      let matchPercent = Math.min(98, Math.round(60 + (jaccardScore * 38)));
      if (sharedCount === 0 && attTags.length > 0) matchPercent = 75;

      matches.push({
        userId: att.UserID,
        name: att.Name,
        email: att.Email,
        role: att.Role,
        matchPercent,
        sharedTags: attTags.filter(t => myTags.has(t)),
        allTags: attTags.length > 0 ? attTags : ['Technology', 'Problem Solving']
      });
    }

    matches.sort((a, b) => b.matchPercent - a.matchPercent);
    res.json(matches);
  } catch (err) {
    console.error('AI Matchmaker Error:', err);
    res.status(500).json({ error: 'Failed to calculate AI matchmaking recommendations.' });
  }
});

// GET Issue or Fetch Cryptographic Digital Certificate for Checked-In Booking
app.get('/api/certificates/:bookingId', authMiddleware, async (req, res) => {
  const bookingId = req.params.bookingId;
  const currentUserId = req.user.UserID;

  try {
    const [bookings] = await pool.query(
      `SELECT B.BookingID, B.UserID, B.AttendanceStatus, B.BookingDate,
              E.EventID, E.Title AS EventTitle, E.Category, E.Date AS EventDate, E.Time AS EventTime, E.Venue,
              U.Name AS AttendeeName, U.Email AS AttendeeEmail,
              Org.Name AS OrganizerName
       FROM Bookings B
       INNER JOIN Events E ON B.EventID = E.EventID
       INNER JOIN Users U ON B.UserID = U.UserID
       INNER JOIN Users Org ON E.OrganizerID = Org.UserID
       WHERE B.BookingID = ?`,
      [bookingId]
    );

    if (bookings.length === 0) {
      return res.status(404).json({ error: 'Booking record not found.' });
    }

    const b = bookings[0];

    if (b.UserID !== currentUserId && req.user.Role !== 'Admin' && req.user.Role !== 'Organizer') {
      return res.status(403).json({ error: 'Access denied. You do not own this certificate.' });
    }

    const isCheckedIn = (b.AttendanceStatus === 'Checked-In' || b.AttendanceStatus === 'Checked In');
    if (!isCheckedIn && req.user.Role !== 'Admin') {
      return res.status(400).json({ error: 'Certificate unavailable. Gate check-in has not been marked by the event organizer yet.' });
    }

    const [certs] = await pool.query('SELECT CertID, CertHash, IssuedAt FROM Certificates WHERE BookingID = ?', [bookingId]);
    let certHash, issuedAt;

    if (certs.length > 0) {
      certHash = certs[0].CertHash;
      issuedAt = certs[0].IssuedAt;
    } else {
      const rawString = `EVENTORBIT-CERT-B${b.BookingID}-U${b.UserID}-E${b.EventID}-${Date.now()}-SALT99`;
      certHash = crypto.createHash('sha256').update(rawString).digest('hex');

      await pool.query(
        'INSERT INTO Certificates (BookingID, CertHash) VALUES (?, ?)',
        [bookingId, certHash]
      );
      issuedAt = new Date().toISOString();
    }

    res.json({
      certId: `CERT-ES-${bookingId}`,
      certHash,
      issuedAt,
      attendeeName: b.AttendeeName,
      attendeeEmail: b.AttendeeEmail,
      eventTitle: b.EventTitle,
      category: b.Category,
      eventDate: b.EventDate,
      venue: b.Venue,
      organizerName: b.OrganizerName
    });

  } catch (err) {
    console.error('Certificate Fetch Error:', err);
    res.status(500).json({ error: 'Failed to process digital certificate.' });
  }
});

// GET Public Cryptographic Certificate Verification Node
app.get('/api/certificates/verify/:hash', async (req, res) => {
  const hash = req.params.hash;

  try {
    const [certs] = await pool.query(
      `SELECT C.CertID, C.CertHash, C.IssuedAt,
              B.BookingID, B.AttendanceStatus,
              E.Title AS EventTitle, E.Category, E.Date AS EventDate, E.Venue,
              U.Name AS AttendeeName, U.Email AS AttendeeEmail,
              Org.Name AS OrganizerName
       FROM Certificates C
       INNER JOIN Bookings B ON C.BookingID = B.BookingID
       INNER JOIN Events E ON B.EventID = E.EventID
       INNER JOIN Users U ON B.UserID = U.UserID
       INNER JOIN Users Org ON E.OrganizerID = Org.UserID
       WHERE C.CertHash = ? OR C.BookingID = ?`,
      [hash, hash.replace(/\D/g, '') || 0]
    );

    if (certs.length === 0) {
      return res.status(404).json({ error: 'Certificate SHA-256 hash or Certificate ID is invalid or does not exist.' });
    }

    const c = certs[0];
    res.json({
      verified: true,
      certId: `CERT-ES-${c.BookingID}`,
      certHash: c.CertHash,
      issuedAt: c.IssuedAt,
      attendeeName: c.AttendeeName,
      attendeeEmail: c.AttendeeEmail,
      eventTitle: c.EventTitle,
      category: c.Category,
      eventDate: c.EventDate,
      venue: c.Venue,
      organizerName: c.OrganizerName
    });

  } catch (err) {
    console.error('Certificate Verify Error:', err);
    res.status(500).json({ error: 'Failed to verify certificate.' });
  }
});

function getNetworkIp() {
  const interfaces = os.networkInterfaces();
  for (const name of Object.keys(interfaces)) {
    for (const iface of interfaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) {
        return iface.address;
      }
    }
  }
  return 'localhost';
}

let publicTunnelUrl = null;

app.get('/api/config', (req, res) => {
  const localIp = getNetworkIp();
  res.json({
    port: PORT,
    localIp,
    localUrl: `http://${localIp}:${PORT}`,
    publicTunnelUrl: publicTunnelUrl || null
  });
});

// Serve frontend UI
app.get('*', (req, res) => {
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

// Start Server
app.listen(PORT, async () => {
  const localIp = getNetworkIp();
  console.log(`==========================================================`);
  console.log(`Event Management System Server running on port ${PORT}`);
  console.log(`Local Access: http://localhost:${PORT}`);
  console.log(`LAN Access:   http://${localIp}:${PORT}`);
  console.log(`==========================================================`);

  try {
    const localtunnel = require('localtunnel');
    const tunnel = await localtunnel({ port: PORT });
    publicTunnelUrl = tunnel.url;
    console.log(`==========================================================`);
    console.log(`🌐 PUBLIC WORLDWIDE ACCESSIBLE QR TUNNEL:`);
    console.log(`👉 ${tunnel.url}`);
    console.log(`(Any phone anywhere in the world can scan QR codes!)`);
    console.log(`==========================================================`);
    tunnel.on('close', () => {
      publicTunnelUrl = null;
    });
  } catch (err) {
    console.log('Localtunnel notice:', err.message);
  }
});
