package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    // Nullable so existing databases can be upgraded without rewriting historical rows.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProjectStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User owner;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected Project() {
    }

    public Project(String name, String description, User owner) {
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.status = ProjectStatus.PLANNED;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProjectStatus getStatus() { return status == null ? ProjectStatus.ACTIVE : status; }
    public User getOwner() { return owner; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void edit(String name, String description) { this.name = name; this.description = description; }
    public void changeStatus(ProjectStatus status) { this.status = status; }
}
