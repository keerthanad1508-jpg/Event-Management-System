# EventSphere - College Event Management System (DBMS Project)

EventSphere is a single-page web application built for college DBMS lab evaluations. It highlights modern UI/UX design alongside robust database interactions using **raw relational SQL queries** (no ORMs) and atomic **SQL transactions**.

---

## Technical Stack
- **Frontend:** HTML5, CSS3, Tailwind CSS (via CDN), Vanilla JavaScript (Fetch API)
- **Backend:** Node.js, Express.js
- **Database:** MySQL (using `mysql2/promise` connection pool)
- **Dependencies:** `express`, `mysql2`, `bcryptjs`, `dotenv`, `cors`

---

## Features Showcase
1. **Authentication:** Register and Sign In with role permissions validation (`Admin`, `Organizer`, `Attendee`).
2. **Attendee Dashboard:**
   - Dynamic searching and price filtering.
   - **Ticket Booking Transaction:** Atomically books tickets (inserts booking and decrements available slots inside a database `START TRANSACTION` / `COMMIT` / `ROLLBACK` safety block with row-locking).
   - **Registered Tickets List:** Displays all booked events using an `INNER JOIN` query between `Bookings`, `Events`, and `Users`.
   - **Cancellation Transaction:** Releases booked tickets and increments slot capacity.
3. **Organizer Dashboard:**
   - Full event CRUD operations (create, update, delete events).
   - Smart available slots calculation on capacity updates.
   - Aggregate sales and estimated revenue overview metrics.
4. **Admin Dashboard:**
   - System stats report (Total Users, Events, Bookings).
   - SQL aggregation reports (user roles distribution, top popular events by ticket sales).

---

## Project Structure
```
Event Mngt System/
├── schema.sql           # Database schema & seed data
├── server.js            # Node/Express server and API controller
├── package.json         # Node.js dependencies configuration
├── .env                 # Database credentials configuration
├── README.md            # Setup and running instructions
├── tasks.md             # Development task tracker
└── public/              # Static frontend assets
    ├── index.html       # UI layout structure
    └── app.js           # Client-side routing and Fetch controller
```

---

## Installation & Setup Instructions

### Step 1: Database Setup
1. Open your MySQL client (Command Line Client, MySQL Workbench, XAMPP, or phpMyAdmin).
2. Create the target database:
   ```sql
   CREATE DATABASE event_management_db;
   USE event_management_db;
   ```
3. Import the DDL script and mock data by executing the contents of `schema.sql`:
   - *Via CLI Command:*
     ```bash
     mysql -u root -p event_management_db < schema.sql
     ```
   - *Alternative:* Copy the contents of `schema.sql`, paste it into a SQL query editor, and click run.

### Step 2: Configure Environment Variables
1. Look at the `.env` file in the root directory.
2. Edit the database credentials to match your local installation:
   ```env
   PORT=5000
   DB_HOST=localhost
   DB_PORT=3306
   DB_USER=root
   DB_PASSWORD=your_mysql_password
   DB_NAME=event_management_db
   ```

### Step 3: Install Node.js Dependencies
Open your terminal inside the project directory (`Event Mngt System`) and run:
```bash
npm install
```

### Step 4: Run the Application Server
Start the development server:
```bash
npm run dev
```
*The server will start running at:* **`http://localhost:5000`**

---

## Demo Test Credentials
The database is initialized with default mock users for testing. All mock users share the same password: **`password123`**

| Role | Email Address | Description |
|---|---|---|
| **System Admin** | `admin@college.edu` | View database metrics, aggregation charts. |
| **Organizer** | `cs.org@college.edu` | CRUD events, view slot availability charts. |
| **Attendee** | `alice@college.edu` | Browse catalog, book tickets, check booking history. |
| **Attendee** | `bob@college.edu` | Browse catalog, book tickets, check booking history. |
