package com.aimanage.prototype.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "project_member", uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "user_id"}))
public class ProjectMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id")
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MemberStatus status = MemberStatus.ACTIVE;
    @Column(length = 50)
    private String projectIdentity;
    @Column(length = 500)
    private String responsibilities;

    protected ProjectMember() {
    }

    public ProjectMember(Project project, User user) {
        this.project = project;
        this.user = user;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public User getUser() { return user; }
    public MemberStatus getStatus() { return status == null ? MemberStatus.ACTIVE : status; }
    public String getProjectIdentity() { return projectIdentity == null || projectIdentity.isBlank() ? "开发人员" : projectIdentity; }
    public String getResponsibilities() { return responsibilities == null ? "" : responsibilities; }
    public void updateDetails(MemberStatus status, String identity, String responsibilities) {
        this.status = status; this.projectIdentity = identity; this.responsibilities = responsibilities;
    }
}
