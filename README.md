# 🏥 Hospital Real-Time Alert Monitoring System

A Java-based real-time hospital alert system that monitors a live MySQL database every 30 seconds and generates a dynamic HTML dashboard with color-coded alerts based on patient data.

---

## 🎬 Demo

| Alert Level | Ball Color | Condition |
|---|---|---|
| 🟢 NORMAL | Green | All vitals stable |
| 🟡 WARNING | Amber | Bed >80%, Meds missed, ER wait >45min |
| 🔴 CRITICAL | Red | Critical vitals, Labs pending |
| 🟣 SEVERE | Purple + Shake | Multiple critical vitals, ER wait >90min |

> The dashboard auto-refreshes every 30 seconds. Ball color, speed, and size change live based on real database values.

---

## 🚀 Features

- **Real-time DB polling** — reads MySQL every 30 seconds using JDBC
- **5 table monitoring** — patient vitals, bed occupancy, medication alerts, ER queue, lab results
- **Medical threshold logic** — heart rate >130 bpm, SpO2 <90%, systolic BP >180 mmHg
- **4 alert levels** — NORMAL / WARNING / CRITICAL / SEVERE with escalation logic
- **Live HTML dashboard** — animated ball visualization generated from Java, auto-opens in browser
- **No manual refresh needed** — dashboard auto-refreshes every 30 seconds

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java (JDK 17+) |
| Database | MySQL 8.0+ |
| DB Driver | MySQL Connector/J 9.6.0 |
| Frontend | HTML5, CSS3, JavaScript (Canvas API) |

---

## 🗄️ Database Schema

The system expects a MySQL database named `health_ehr` with these tables:

```sql
-- 1. Patient Vitals
CREATE TABLE patient_vitals (
    id INT AUTO_INCREMENT PRIMARY KEY,
    patient_id INT,
    heart_rate DOUBLE,
    spo2 DOUBLE,
    systolic_bp DOUBLE,
    recorded_at DATETIME DEFAULT NOW()
);

-- 2. Bed Occupancy
CREATE TABLE bed_occupancy (
    id INT AUTO_INCREMENT PRIMARY KEY,
    ward_name VARCHAR(50),
    occupied_beds INT,
    total_beds INT,
    snapshot_time DATETIME DEFAULT NOW()
);

-- 3. Medication Alerts
CREATE TABLE medication_alerts (
    id INT AUTO_INCREMENT PRIMARY KEY,
    patient_id INT,
    medicine_name VARCHAR(100),
    scheduled_at DATETIME,
    acknowledged BOOLEAN DEFAULT FALSE
);

-- 4. ER Queue
CREATE TABLE er_queue (
    id INT AUTO_INCREMENT PRIMARY KEY,
    patient_id INT,
    triage_level VARCHAR(5),
    wait_minutes INT,
    status VARCHAR(20),
    created_at DATETIME DEFAULT NOW()
);

-- 5. Lab Results
CREATE TABLE lab_results (
    id INT AUTO_INCREMENT PRIMARY KEY,
    patient_id INT,
    test_name VARCHAR(100),
    flag_status VARCHAR(20),
    resulted_at DATETIME DEFAULT NOW()
);
```

---

## ⚙️ Setup & Run

### Prerequisites
- Java JDK 17 or higher
- MySQL 8.0+
- `mysql-connector-j-9.6.0.jar` (included in repo)

### Steps

**1. Clone the repository**
```bash
git clone https://github.com/YOUR_USERNAME/hospital-alert-system.git
cd hospital-alert-system
```

**2. Set up MySQL database**
```sql
CREATE DATABASE health_ehr;
USE health_ehr;
-- Run the schema SQL above
```

**3. Update credentials in `HealthDBAlertSystem.java`**
```java
static final String DB_URL  = "jdbc:mysql://localhost:3306/health_ehr...";
static final String DB_USER = "root";
static final String DB_PASS = "your_password";
```

**4. Compile**
```bash
javac -cp .;mysql-connector-j-9.6.0.jar HealthDBAlertSystem.java
```

**5. Run**
```bash
java -cp .;mysql-connector-j-9.6.0.jar HealthDBAlertSystem
```

> On Linux/Mac use `:` instead of `;` in classpath

The system will:
- Connect to MySQL
- Read all 5 tables
- Generate `HealthAlertDashboard.html`
- Auto-open it in your browser
- Poll every 30 seconds and update the dashboard

---

## 🔔 Alert Thresholds

| Metric | WARNING | CRITICAL | SEVERE |
|---|---|---|---|
| Critical Vitals (count) | — | 1-2 patients | 3+ patients |
| Bed Occupancy | ≥80% | ≥95% | — |
| Missed Medications | ≥5 | ≥15 | — |
| ER P1 Wait Time | >45 min | — | >90 min |
| Critical Lab Results | 1-4 | ≥5 | — |

---

## 📁 Project Structure

```
hospital-alert-system/
│
├── HealthDBAlertSystem.java      # Main Java source file
├── HealthDBAlertSystem.class     # Compiled class
├── mysql-connector-j-9.6.0.jar  # MySQL JDBC driver
├── HealthAlertDashboard.html     # Generated dashboard (auto-created on run)
└── README.md
```

---

## 📸 Dashboard Preview

The dashboard shows:
- **5 live metric cards** — vitals, bed %, missed meds, ER wait, lab results
- **Animated ball** — bounces and changes color/speed based on alert level
- **Alert log** — live list of active warnings from the database
- **Clock** — real-time clock updating every second

---

## 👨‍💻 Author

**Divyansh Rai**
B.Tech Information Technology — Government Engineering College, Ajmer
📧 divyansharye@gmail.com

---

## 📄 License

This project is open source and available under the [MIT License](LICENSE).
