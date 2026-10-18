import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.security.SecureRandom;

public class MigrateH2ToMysql {
    private static final String MYSQL = "jdbc:mysql://127.0.0.1:3306/";
    private static final String OPTIONS = "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false";
    private static final Map<String, String> TABLES = new LinkedHashMap<>();
    static {
        TABLES.put("app_user", "id,display_name,password,role,username");
        TABLES.put("project", "id,created_at,description,name,owner_id");
        TABLES.put("project_member", "id,project_id,user_id");
        TABLES.put("project_task", "id,description,due_date,status,title,updated_at,assignee_id,project_id");
    }

    public static void main(String[] args) throws Exception {
        String adminPassword = Objects.requireNonNull(System.getenv("AIMANAGE_MYSQL_ADMIN_PASSWORD"), "Admin password environment variable required");
        String h2Url = "jdbc:h2:file:./data/aimanage;MODE=MySQL;DATABASE_TO_LOWER=TRUE;IFEXISTS=TRUE";
        Files.createDirectories(Path.of(".local"));
        try (Connection source = DriverManager.getConnection(h2Url, "sa", "");
             Connection admin = DriverManager.getConnection(MYSQL + OPTIONS, "root", adminPassword)) {
            try (Statement s = admin.createStatement(); ResultSet r = s.executeQuery("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='aimanage'")) {
                if (r.next()) throw new IllegalStateException("Target database already exists; refusing to overwrite");
            }
            try (Statement s = admin.createStatement(); ResultSet r = s.executeQuery("SELECT User FROM mysql.user WHERE User='aimanage_app'")) {
                if (r.next()) throw new IllegalStateException("Application account already exists; refusing to overwrite");
            }
            try (Statement s = source.createStatement()) {
                String backup = Path.of(".local", "h2-before-mysql-" + System.currentTimeMillis() + ".zip").toAbsolutePath().toString().replace("'", "''");
                s.execute("BACKUP TO '" + backup + "'");
                System.out.println("H2 backup created");
            }
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            String appPassword = HexFormat.of().formatHex(bytes);
            try (Statement s = admin.createStatement()) {
                s.execute("CREATE DATABASE aimanage CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                s.execute("CREATE USER 'aimanage_app'@'localhost' IDENTIFIED BY '" + appPassword + "'");
                s.execute("GRANT ALL PRIVILEGES ON aimanage.* TO 'aimanage_app'@'localhost'");
            }
            // Save local credentials before copying, so a failed migration remains inspectable.
            Properties config = new Properties();
            config.setProperty("spring.datasource.url", MYSQL + "aimanage" + OPTIONS);
            config.setProperty("spring.datasource.username", "aimanage_app");
            config.setProperty("spring.datasource.password", appPassword);
            try (var writer = Files.newBufferedWriter(Path.of(".local", "mysql.properties"))) {
                config.store(writer, "Local MySQL connection; do not commit");
            }
            try (Connection target = DriverManager.getConnection(MYSQL + "aimanage" + OPTIONS, "aimanage_app", appPassword)) {
                createTables(target);
                target.setAutoCommit(false);
                try {
                    for (var table : TABLES.entrySet()) copy(source, target, table.getKey(), table.getValue());
                    verify(source, target);
                    target.commit();
                } catch (Exception ex) {
                    target.rollback();
                    throw ex;
                }
                System.out.println("Migration committed; all rows and columns verified");
            }
        }
    }

    private static void createTables(Connection c) throws SQLException {
        String[] ddl = {
            "CREATE TABLE app_user (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, display_name VARCHAR(50) NOT NULL, password VARCHAR(100) NOT NULL, role ENUM('DEVELOPER','PROJECT_MANAGER') NOT NULL, username VARCHAR(50) NOT NULL UNIQUE) ENGINE=InnoDB",
            "CREATE TABLE project (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, created_at DATETIME(6) NOT NULL, description VARCHAR(500) NOT NULL, name VARCHAR(100) NOT NULL, owner_id BIGINT NOT NULL, CONSTRAINT fk_project_owner FOREIGN KEY(owner_id) REFERENCES app_user(id)) ENGINE=InnoDB",
            "CREATE TABLE project_member (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, project_id BIGINT NOT NULL, user_id BIGINT NOT NULL, UNIQUE KEY uq_project_user(project_id,user_id), CONSTRAINT fk_member_project FOREIGN KEY(project_id) REFERENCES project(id), CONSTRAINT fk_member_user FOREIGN KEY(user_id) REFERENCES app_user(id)) ENGINE=InnoDB",
            "CREATE TABLE project_task (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, description VARCHAR(1000) NOT NULL, due_date DATE, status ENUM('DONE','IN_PROGRESS','TODO') NOT NULL, title VARCHAR(120) NOT NULL, updated_at DATETIME(6) NOT NULL, assignee_id BIGINT NOT NULL, project_id BIGINT NOT NULL, CONSTRAINT fk_task_assignee FOREIGN KEY(assignee_id) REFERENCES app_user(id), CONSTRAINT fk_task_project FOREIGN KEY(project_id) REFERENCES project(id)) ENGINE=InnoDB"
        };
        try (Statement s = c.createStatement()) { for (String sql : ddl) s.execute(sql); }
    }

    private static void copy(Connection source, Connection target, String table, String columns) throws SQLException {
        int count = columns.split(",").length;
        String placeholders = String.join(",", Collections.nCopies(count, "?"));
        try (Statement s = source.createStatement();
             ResultSet rows = s.executeQuery("SELECT " + columns + " FROM " + table + " ORDER BY id");
             PreparedStatement insert = target.prepareStatement("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")")) {
            int total = 0;
            while (rows.next()) {
                for (int i = 1; i <= count; i++) insert.setObject(i, rows.getObject(i));
                insert.executeUpdate();
                total++;
            }
            System.out.println(table + ": " + total + " rows copied");
        }
    }

    private static void verify(Connection source, Connection target) throws SQLException {
        for (var table : TABLES.entrySet()) {
            String sql = "SELECT " + table.getValue() + " FROM " + table.getKey() + " ORDER BY id";
            String[] columns = table.getValue().split(",");
            try (Statement a = source.createStatement(); Statement b = target.createStatement();
                 ResultSet left = a.executeQuery(sql); ResultSet right = b.executeQuery(sql)) {
                while (left.next()) {
                    if (!right.next()) throw new SQLException("Missing row: " + table.getKey());
                    for (int i = 0; i < columns.length; i++) {
                        if (!Objects.equals(value(left, i + 1, columns[i]), value(right, i + 1, columns[i]))) {
                            throw new SQLException("Data mismatch: " + table.getKey() + "." + columns[i]);
                        }
                    }
                }
                if (right.next()) throw new SQLException("Extra rows: " + table.getKey());
            }
        }
    }

    private static Object value(ResultSet rows, int index, String column) throws SQLException {
        if (column.endsWith("_at")) return rows.getTimestamp(index);
        if (column.equals("due_date")) return rows.getDate(index);
        if (column.equals("id") || column.endsWith("_id")) return rows.getLong(index);
        return rows.getString(index);
    }
}
