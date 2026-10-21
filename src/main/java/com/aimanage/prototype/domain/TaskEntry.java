package com.aimanage.prototype.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 评论和操作记录共用时间线；保存作者姓名快照，避免资料变更改写历史。 */
@Entity
@Table(name = "task_entry")
public class TaskEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Task task;
    @Column(nullable = false, length = 20)
    private String kind;
    @Column(nullable = false)
    private Long authorId;
    @Column(nullable = false, length = 50)
    private String authorName;
    @Column(nullable = false, length = 2000)
    private String content;
    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected TaskEntry() {}
    public TaskEntry(Task task, User author, String kind, String content) {
        this.task = task;
        this.authorId = author.getId();
        this.authorName = author.getDisplayName();
        this.kind = kind;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }
    public Long getId() { return id; }
    public String getKind() { return kind; }
    public Long getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public String getContent() { return content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
