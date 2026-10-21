package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "task_reminder_read", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "task_id", "due_date"}))
public class TaskReminderRead {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id")
    private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id")
    private Task task;
    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;
    @Column(nullable = false)
    private LocalDateTime readAt;

    protected TaskReminderRead() {}
    public TaskReminderRead(User user, Task task) {
        this.user = user;
        this.task = task;
        this.dueDate = task.getDueDate();
        this.readAt = LocalDateTime.now();
    }
    public Long getId() { return id; }
}
