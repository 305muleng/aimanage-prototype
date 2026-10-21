package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "project_status_change")
public class ProjectStatusChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Project project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User actor;
    @Enumerated(EnumType.STRING) @Column(length = 20)
    private ProjectStatus previousStatus;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ProjectStatus nextStatus;
    @Column(nullable = false, length = 500)
    private String reason;
    @Column(nullable = false)
    private LocalDateTime changedAt;

    protected ProjectStatusChange() {}
    public ProjectStatusChange(Project project, User actor, ProjectStatus previousStatus,
                               ProjectStatus nextStatus, String reason) {
        this.project = project;
        this.actor = actor;
        this.previousStatus = previousStatus;
        this.nextStatus = nextStatus;
        this.reason = reason;
        this.changedAt = LocalDateTime.now();
    }
    public Long getId() { return id; }
    public Project getProject() { return project; }
    public User getActor() { return actor; }
    public ProjectStatus getPreviousStatus() { return previousStatus; }
    public ProjectStatus getNextStatus() { return nextStatus; }
    public String getReason() { return reason; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
