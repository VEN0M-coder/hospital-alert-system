import java.sql.*;
import java.util.*;
import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * HealthDBAlertSystem.java
 *
 * HOW IT WORKS:
 *  1. Reads your MySQL health_ehr database every 30 seconds
 *  2. Checks patient_vitals, bed_occupancy, medication_alerts,
 *     er_queue, lab_results tables
 *  3. Decides alert level: NORMAL / WARNING / CRITICAL / SEVERE
 *  4. Writes HealthAlertDashboard.html with real ball color + speed
 *  5. Opens browser automatically — ball changes based on DB data!
 *
 * Run:
 *   javac -cp .;mysql-connector-j-9.6.0.jar HealthDBAlertSystem.java
 *   java  -cp .;mysql-connector-j-9.6.0.jar HealthDBAlertSystem
 */
public class HealthDBAlertSystem {

    // ── YOUR MYSQL CREDENTIALS ────────────────────────────────────────────────
    static final String DB_URL  = "jdbc:mysql://localhost:3306/health_ehr"
                                + "?useSSL=false&serverTimezone=UTC"
                                + "&allowPublicKeyRetrieval=true";
    static final String DB_USER = "root";
    static final String DB_PASS = "2005";

    // ── THRESHOLDS ────────────────────────────────────────────────────────────
    static final double HR_CRITICAL   = 130.0;
    static final double SPO2_CRITICAL = 90.0;
    static final double SBP_CRITICAL  = 180.0;
    static final int    BED_WARN_PCT  = 80;
    static final int    BED_CRIT_PCT  = 95;
    static final int    MED_WARN      = 5;
    static final int    MED_CRIT      = 15;
    static final int    ER_WARN_MIN   = 45;
    static final int    ER_CRIT_MIN   = 90;

    enum Level { NORMAL, WARNING, CRITICAL, SEVERE }

    static Level escalate(Level cur, Level next) {
        return next.ordinal() > cur.ordinal() ? next : cur;
    }

    // ── READ EACH TABLE ───────────────────────────────────────────────────────

    static int countCriticalVitals(Connection c) throws SQLException {
        String sql = "SELECT COUNT(*) FROM patient_vitals "
                   + "WHERE recorded_at >= NOW() - INTERVAL 15 MINUTE "
                   + "AND (heart_rate > ? OR spo2 < ? OR systolic_bp > ?)";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDouble(1, HR_CRITICAL);
            ps.setDouble(2, SPO2_CRITICAL);
            ps.setDouble(3, SBP_CRITICAL);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static int maxBedPct(Connection c) throws SQLException {
        String sql = "SELECT COALESCE(MAX(occupied_beds * 100 / total_beds), 0) "
                   + "FROM bed_occupancy "
                   + "WHERE snapshot_time = (SELECT MAX(snapshot_time) FROM bed_occupancy)";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static int countMissedMeds(Connection c) throws SQLException {
        String sql = "SELECT COUNT(*) FROM medication_alerts "
                   + "WHERE acknowledged = FALSE AND scheduled_at < NOW()";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static int maxErWait(Connection c) throws SQLException {
        String sql = "SELECT COALESCE(MAX(wait_minutes), 0) FROM er_queue "
                   + "WHERE triage_level = 'P1' AND status = 'WAITING'";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    static int countCriticalLabs(Connection c) throws SQLException {
        String sql = "SELECT COUNT(*) FROM lab_results "
                   + "WHERE resulted_at >= NOW() - INTERVAL 1 HOUR "
                   + "AND flag_status IN ('CRITICAL_HIGH','CRITICAL_LOW')";
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ── EVALUATE ALL DATA AND DECIDE LEVEL ───────────────────────────────────

    static class DBResult {
        Level        level  = Level.NORMAL;
        List<String> alerts = new ArrayList<>();
        int vitals, bedPct, meds, erWait, labs;
    }

    static DBResult evaluate(Connection conn) throws SQLException {
        DBResult r = new DBResult();

        r.vitals = countCriticalVitals(conn);
        r.bedPct = maxBedPct(conn);
        r.meds   = countMissedMeds(conn);
        r.erWait = maxErWait(conn);
        r.labs   = countCriticalLabs(conn);

        // Vitals check
        if (r.vitals >= 3) {
            r.level = escalate(r.level, Level.SEVERE);
            r.alerts.add("SEVERE: " + r.vitals + " patients with critical vitals");
        } else if (r.vitals > 0) {
            r.level = escalate(r.level, Level.CRITICAL);
            r.alerts.add("CRITICAL: " + r.vitals + " patient(s) with abnormal vitals");
        }

        // Bed occupancy check
        if (r.bedPct >= BED_CRIT_PCT) {
            r.level = escalate(r.level, Level.CRITICAL);
            r.alerts.add("CRITICAL: Ward at " + r.bedPct + "% capacity");
        } else if (r.bedPct >= BED_WARN_PCT) {
            r.level = escalate(r.level, Level.WARNING);
            r.alerts.add("WARNING: Ward at " + r.bedPct + "% capacity");
        }

        // Missed medications check
        if (r.meds >= MED_CRIT) {
            r.level = escalate(r.level, Level.CRITICAL);
            r.alerts.add("CRITICAL: " + r.meds + " missed medication doses");
        } else if (r.meds >= MED_WARN) {
            r.level = escalate(r.level, Level.WARNING);
            r.alerts.add("WARNING: " + r.meds + " missed doses");
        }

        // ER wait check
        if (r.erWait > ER_CRIT_MIN) {
            r.level = escalate(r.level, Level.SEVERE);
            r.alerts.add("SEVERE: ER P1 wait = " + r.erWait + " min");
        } else if (r.erWait > ER_WARN_MIN) {
            r.level = escalate(r.level, Level.WARNING);
            r.alerts.add("WARNING: ER P1 wait = " + r.erWait + " min");
        }

        // Lab results check
        if (r.labs >= 5) {
            r.level = escalate(r.level, Level.CRITICAL);
            r.alerts.add("CRITICAL: " + r.labs + " critical lab results pending");
        } else if (r.labs > 0) {
            r.level = escalate(r.level, Level.WARNING);
            r.alerts.add("WARNING: " + r.labs + " critical lab result(s)");
        }

        if (r.alerts.isEmpty()) {
            r.alerts.add("All systems normal. No active alerts.");
        }

        return r;
    }

    // ── WRITE HTML FILE WITH REAL VALUES FROM DB ──────────────────────────────

    static void writeHtml(DBResult r) throws IOException {

        // These values come directly from DB evaluation
        String  color;
        double  speed;
        int     radius;
        boolean shake;
        String  badgeBg, badgeFg;

        switch (r.level) {
            case NORMAL -> {
                color="#1D9E75"; speed=1.2;  radius=20; shake=false;
                badgeBg="#0a2e20"; badgeFg="#1D9E75";
            }
            case WARNING -> {
                color="#BA7517"; speed=3.5;  radius=24; shake=false;
                badgeBg="#2e1f00"; badgeFg="#BA7517";
            }
            case CRITICAL -> {
                color="#D85A30"; speed=7.0;  radius=28; shake=false;
                badgeBg="#2e1000"; badgeFg="#D85A30";
            }
            default -> {  // SEVERE
                color="#7F77DD"; speed=12.0; radius=32; shake=true;
                badgeBg="#1a1040"; badgeFg="#7F77DD";
            }
        }

        String now = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"));

        // Build alert rows
        StringBuilder alertRows = new StringBuilder();
        for (String a : r.alerts) {
            String dot = a.startsWith("SEVERE")   ? "#E24B4A"
                       : a.startsWith("CRITICAL") ? "#D85A30"
                       : a.startsWith("WARNING")  ? "#BA7517"
                                                   : "#1D9E75";
            alertRows.append("<div class='ai'>"
                + "<span class='dot' style='background:" + dot + "'></span>"
                + "<span>" + a + "</span></div>\n");
        }

        // Full HTML — all values injected from Java variables above
        String html =
            "<!DOCTYPE html>\n<html>\n<head>\n"
          + "<meta charset='UTF-8'>\n"
          + "<meta http-equiv='refresh' content='30'>\n"  // auto-refresh every 30s
          + "<title>Health Alert - " + r.level + "</title>\n"
          + "<style>\n"
          + "*{box-sizing:border-box;margin:0;padding:0}\n"
          + "body{font-family:Arial,sans-serif;background:#0f0f1a;color:#e0e0e0;padding:20px}\n"
          + "h1{font-size:21px;color:#fff;display:inline}\n"
          + ".badge{font-size:12px;font-weight:700;padding:4px 14px;border-radius:20px;"
          +        "margin-left:12px;vertical-align:middle;"
          +        "background:" + badgeBg + ";color:" + badgeFg + "}\n"
          + ".sub{font-size:12px;color:#555;margin:6px 0 16px}\n"
          + ".metrics{display:grid;grid-template-columns:repeat(5,1fr);gap:10px;margin-bottom:16px}\n"
          + ".mc{background:#1a1a2e;border:1px solid #2a2a45;border-radius:10px;padding:12px}\n"
          + ".lb{font-size:11px;color:#555;margin-bottom:4px}\n"
          + ".val{font-size:22px;font-weight:700;color:" + color + "}\n"
          + ".sb{font-size:11px;color:#444;margin-top:2px}\n"
          + ".arena{position:relative;background:#12122a;border:1px solid #2a2a45;"
          +        "border-radius:14px;overflow:hidden;height:300px;margin-bottom:14px}\n"
          + ".al{position:absolute;top:12px;left:16px;font-size:11px;color:#333;"
          +     "letter-spacing:.05em;text-transform:uppercase;z-index:2}\n"
          + ".clk{position:absolute;top:12px;right:16px;font-size:12px;color:#444;"
          +      "font-family:monospace;z-index:2}\n"
          + "canvas{width:100%;height:100%}\n"
          + ".abox{background:#1a1a2e;border:1px solid #2a2a45;border-radius:10px;padding:14px}\n"
          + ".atitle{font-size:11px;color:#444;text-transform:uppercase;letter-spacing:.05em;margin-bottom:8px}\n"
          + ".ai{display:flex;align-items:flex-start;gap:8px;font-size:13px;color:#aaa;padding:3px 0}\n"
          + ".dot{width:7px;height:7px;border-radius:50%;margin-top:4px;flex-shrink:0;display:inline-block}\n"
          + ".footer{text-align:center;font-size:11px;color:#333;margin-top:12px}\n"
          + "</style>\n</head>\n<body>\n"

          + "<h1>Health Enterprise Alert Monitor</h1>"
          + "<span class='badge'>" + r.level + "</span>\n"
          + "<div class='sub'>Last DB poll: " + now + " | Auto-refreshes every 30 sec</div>\n"

          + "<div class='metrics'>\n"
          + "<div class='mc'><div class='lb'>Critical vitals</div>"
          +    "<div class='val'>" + r.vitals + "</div><div class='sb'>last 15 min</div></div>\n"
          + "<div class='mc'><div class='lb'>Bed occupancy</div>"
          +    "<div class='val'>" + r.bedPct + "%</div><div class='sb'>max ward</div></div>\n"
          + "<div class='mc'><div class='lb'>Missed meds</div>"
          +    "<div class='val'>" + r.meds + "</div><div class='sb'>unacknowledged</div></div>\n"
          + "<div class='mc'><div class='lb'>ER P1 wait</div>"
          +    "<div class='val'>" + r.erWait + " min</div><div class='sb'>triage P1</div></div>\n"
          + "<div class='mc'><div class='lb'>Critical labs</div>"
          +    "<div class='val'>" + r.labs + "</div><div class='sb'>pending review</div></div>\n"
          + "</div>\n"

          + "<div class='arena' id='arena'>\n"
          + "<span class='al'>Alert: " + r.level + "</span>\n"
          + "<span class='clk' id='clk'></span>\n"
          + "<canvas id='c'></canvas>\n"
          + "</div>\n"

          + "<div class='abox'>\n"
          + "<div class='atitle'>Live alerts from MySQL</div>\n"
          + alertRows
          + "</div>\n"
          + "<div class='footer'>Java reads MySQL every 30 sec &rarr; ball updates automatically</div>\n"

          // ── JAVASCRIPT: all constants injected from Java ──────────────────
          + "<script>\n"
          + "const C='" + color  + "';\n"   // ball color  from DB level
          + "const S="  + speed  + ";\n"    // ball speed  from DB level
          + "const R="  + radius + ";\n"    // ball radius from DB level
          + "const K="  + shake  + ";\n"    // shake flag  from DB level
          + "const cv=document.getElementById('c');\n"
          + "const cx=cv.getContext('2d');\n"
          + "const ar=document.getElementById('arena');\n"
          + "let bx,by,vx,vy,st=0;\n"
          + "function rsz(){\n"
          + "  cv.width=ar.clientWidth;cv.height=ar.clientHeight;\n"
          + "  if(!bx){bx=cv.width/2;by=cv.height/2;}\n"
          + "}\n"
          + "rsz();window.addEventListener('resize',rsz);\n"
          + "bx=200;by=150;\n"
          + "vx=Math.cos(Math.PI/4)*S;vy=Math.sin(Math.PI/4)*S;\n"
          + "function draw(){\n"
          + "  const W=cv.width,H=cv.height;\n"
          + "  bx+=vx;by+=vy;vy+=0.22;\n"
          + "  if(bx-R<0){bx=R;vx=Math.abs(vx);}\n"
          + "  if(bx+R>W){bx=W-R;vx=-Math.abs(vx);}\n"
          + "  if(by+R>H-8){by=H-8-R;vy=-Math.abs(vy)*.80;vx*=.98;}\n"
          + "  if(by-R<30){by=30+R;vy=Math.abs(vy);}\n"
          + "  let ox=0,oy=0;\n"
          + "  if(K){st++;ox=(Math.random()-.5)*8*Math.sin(st*.4);oy=(Math.random()-.5)*6*Math.sin(st*.3);}\n"
          + "  const x=bx+ox,y=by+oy;\n"
          + "  cx.clearRect(0,0,W,H);\n"
          + "  cx.strokeStyle='rgba(255,255,255,0.03)';cx.lineWidth=1;\n"
          + "  for(let i=0;i<W;i+=40){cx.beginPath();cx.moveTo(i,0);cx.lineTo(i,H);cx.stroke();}\n"
          + "  for(let j=0;j<H;j+=40){cx.beginPath();cx.moveTo(0,j);cx.lineTo(W,j);cx.stroke();}\n"
          + "  for(let i=8;i>0;i--){\n"
          + "    const tx=x-vx*i*.3,ty=y-vy*i*.3;\n"
          + "    const a=Math.round(40*(i/8)).toString(16).padStart(2,'0');\n"
          + "    cx.beginPath();cx.arc(tx,ty,R*(i/8)*.65,0,Math.PI*2);\n"
          + "    cx.fillStyle=C+a;cx.fill();}\n"
          + "  if(K){const p=.5+.5*Math.sin(st*.25);\n"
          + "    cx.beginPath();cx.arc(x,y,R*1.5+p*8,0,Math.PI*2);\n"
          + "    cx.strokeStyle=C+'55';cx.lineWidth=3;cx.stroke();\n"
          + "    cx.beginPath();cx.arc(x,y,R*2.2+p*5,0,Math.PI*2);\n"
          + "    cx.strokeStyle=C+'22';cx.lineWidth=2;cx.stroke();}\n"
          + "  const g=cx.createRadialGradient(x-R*.3,y-R*.3,R*.05,x,y,R);\n"
          + "  g.addColorStop(0,C+'ff');g.addColorStop(1,C+'bb');\n"
          + "  cx.beginPath();cx.arc(x,y,R,0,Math.PI*2);cx.fillStyle=g;cx.fill();\n"
          + "  cx.beginPath();cx.arc(x-R*.3,y-R*.3,R*.25,0,Math.PI*2);\n"
          + "  cx.fillStyle='rgba(255,255,255,0.28)';cx.fill();\n"
          + "  const fd=(H-y-R)/H,sw=Math.max(5,R*1.6*(1-fd*1.5));\n"
          + "  cx.beginPath();cx.ellipse(bx,H-8,sw,5,0,0,Math.PI*2);\n"
          + "  cx.fillStyle=`rgba(0,0,0,${Math.max(0,.4*(1-fd*2))})`;cx.fill();\n"
          + "  cx.font='bold 13px Arial';cx.fillStyle=C+'88';cx.textAlign='right';\n"
          + "  cx.fillText('ALERT: " + r.level + "',W-16,H-14);\n"
          + "  requestAnimationFrame(draw);\n"
          + "}\n"
          + "function tick(){\n"
          + "  document.getElementById('clk').textContent=new Date().toLocaleTimeString();}\n"
          + "setInterval(tick,1000);tick();draw();\n"
          + "</script>\n</body>\n</html>";

        try (FileWriter fw = new FileWriter("HealthAlertDashboard.html")) {
            fw.write(html);
        }
    }

    // ── MAIN ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("  Health Enterprise DB Alert Monitor");
        System.out.println("==========================================");
        System.out.println("DB: " + DB_URL);
        System.out.println();

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.out.println("ERROR: MySQL JDBC driver not found!");
            return;
        }

        boolean firstRun = true;

        while (true) {
            String time = LocalDateTime.now()
                            .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            System.out.println("[" + time + "] Reading MySQL database...");

            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {

                // READ ALL 5 TABLES → EVALUATE → GET REAL LEVEL
                DBResult r = evaluate(conn);

                // PRINT RESULTS TO CMD
                System.out.println("==========================================");
                System.out.println("  ALERT LEVEL  : " + r.level);
                System.out.println("  Vitals       : " + r.vitals);
                System.out.println("  Bed Occupancy: " + r.bedPct + "%");
                System.out.println("  Missed Meds  : " + r.meds);
                System.out.println("  ER P1 Wait   : " + r.erWait + " min");
                System.out.println("  Critical Labs: " + r.labs);
                System.out.println("  Ball         : " + switch(r.level){
                    case NORMAL   -> "GREEN  - slow bounce";
                    case WARNING  -> "AMBER  - medium bounce";
                    case CRITICAL -> "RED    - fast bounce";
                    case SEVERE   -> "PURPLE - max speed + shake";
                });
                r.alerts.forEach(a -> System.out.println("  -> " + a));
                System.out.println("==========================================");

                // WRITE HTML WITH REAL VALUES FROM DB
                writeHtml(r);

                // OPEN BROWSER ONCE — then it auto-refreshes itself
                if (firstRun) {
                    java.io.File f = new java.io.File("HealthAlertDashboard.html");
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().browse(f.toURI());
                        System.out.println("Browser opened! Updates every 30 sec automatically.");
                    }
                    firstRun = false;
                } else {
                    System.out.println("HTML updated. Browser refreshes automatically.");
                }

            } catch (SQLException e) {
                System.out.println("DB error: " + e.getMessage());
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }

            System.out.println("Next poll in 30 seconds...\n");
            try { Thread.sleep(30_000); } catch (InterruptedException e) { break; }
        }
    }
}