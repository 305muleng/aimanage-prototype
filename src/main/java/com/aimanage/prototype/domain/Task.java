package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "project_task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TaskStatus status;

    private LocalDate dueDate;

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

    public void edit(String title, String description, LocalDate dueDate, User assignee) {
        this.title = title;
        this.description = description;
        this.dueDate = dueDate;
        this.assignee = assignee;
        this.updatedAt = LocalDateTime.now();
    }

    public void changeStatus(TaskStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskStatus getStatus() { return status; }
    public LocalDate getDueDate() { return dueDate; }
    public Project getProject() { return project; }
    public User getAssignee() { return assignee; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
