package com.aimanage.prototype.domain;

import jakarta.persistence.*;

/** task 必须在 predecessor 之后执行；关系只在同一项目中维护。 */
@Entity
@Table(name = "task_dependency", uniqueConstraints =
        @UniqueConstraint(columnNames = {"task_id", "predecessor_id"}))
public class TaskDependency {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id")
    private Task task;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "predecessor_id")
    private Task predecessor;

    protected TaskDependency() {}
    public TaskDependency(Task task, Task predecessor) {
        this.task = task;
        this.predecessor = predecessor;
    }
    public Long getId() { return id; }
    public Task getTask() { return task; }
    public Task getPredecessor() { return predecessor; }
}
