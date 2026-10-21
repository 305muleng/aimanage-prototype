-- MySQL 8: run against a backed-up database before starting the updated application.
-- Safe to run more than once. Existing project rows without status are treated as ACTIVE.

SET @status_column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'project' AND column_name = 'status'
);
SET @add_status_sql := IF(@status_column_exists = 0,
    'ALTER TABLE project ADD COLUMN status VARCHAR(20) NULL',
    'SELECT 1');
PREPARE add_status_statement FROM @add_status_sql;
EXECUTE add_status_statement;
DEALLOCATE PREPARE add_status_statement;

CREATE TABLE IF NOT EXISTS project_status_change (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    previous_status VARCHAR(20) NULL,
    next_status VARCHAR(20) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    INDEX idx_status_change_project_time (project_id, changed_at)
);

-- Register one baseline for every project without history before replacing NULL statuses.
INSERT INTO project_status_change (project_id, actor_id, previous_status, next_status, reason, changed_at)
SELECT p.id, p.owner_id, NULL, COALESCE(NULLIF(p.status, ''), 'ACTIVE'),
       '旧数据迁移：记录项目状态基线', COALESCE(p.created_at, NOW(6))
FROM project p
WHERE NOT EXISTS (SELECT 1 FROM project_status_change c WHERE c.project_id = p.id);

UPDATE project SET status = 'ACTIVE' WHERE status IS NULL OR status = '';

CREATE TABLE IF NOT EXISTS task_reminder_read (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    due_date DATE NOT NULL,
    read_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_reminder_user_task_due (user_id, task_id, due_date)
);

SELECT COUNT(*) AS projects_without_status FROM project WHERE status IS NULL OR status = '';
SELECT COUNT(*) AS projects_without_history FROM project p
WHERE NOT EXISTS (SELECT 1 FROM project_status_change c WHERE c.project_id = p.id);
