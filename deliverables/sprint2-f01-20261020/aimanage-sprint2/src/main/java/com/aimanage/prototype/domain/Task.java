package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Sprint 1 的任务实体，保存项目归属、负责人、状态和最后更新时间。 */
@Entity
@Table(name = "project_task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 已有任务的版本从 0 开始，避免并发写入覆盖他人的修改。
    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long version;

    @Column(length = 1000)
    private String completionNote;
    private LocalDateTime completedAt;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    private LocalDate dueDate;
    // 可空以兼容旧记录；空值按未阻塞读取。
    private Boolean blocked;
    @Column(length = 500)
    private String blockedReason;
    public boolean isBlocked() { return Boolean.TRUE.equals(blocked); }
    public String getBlockedReason() { return blockedReason == null ? "" : blockedReason; }
    public void setBlock(boolean blocked, String reason) {
        this.blocked = blocked;
        this.blockedReason = blocked ? reason : "";
        this.updatedAt = LocalDateTime.now();
    }
    @Enumerated(EnumType.STRING) private Priority priority;
    @ManyToOne private WorkItem story;
    public Priority getPriority(){return priority==null?Priority.MEDIUM:priority;}
    public WorkItem getStory(){return story;}
    public void plan(Priority priority,WorkItem story){this.priority=priority;this.story=story;this.updatedAt=LocalDateTime.now();}

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User assignee;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected Task() {
    }

    public Task(String title, String description, LocalDate dueDate, Project project, User assignee) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.project = project;
        this.assignee = assignee;
        this.status = TaskStatus.TODO;
        this.updatedAt = LocalDateTime.now();
    }

    /** 编辑任务内容时同步刷新更新时间。 */
    public void edit(String title, String description, LocalDate dueDate, User assignee) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.assignee = assignee;
        this.updatedAt = LocalDateTime.now();
    }

    /** 修改任务状态时同步刷新更新时间。 */
    public void changeStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete(String note) {
        changeStatus(TaskStatus.DONE);
        completionNote = note;
        completedAt = LocalDateTime.now();
    }

    public void reopen(TaskStatus status) {
        changeStatus(status);
        completionNote = null;
        completedAt = null;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getCompletionNote() { return completionNote == null ? "" : completionNote; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskStatus getStatus() { return status; }
    public LocalDate getDueDate() { return dueDate; }
    public Project getProject() { return project; }
    public User getAssignee() { return assignee; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
