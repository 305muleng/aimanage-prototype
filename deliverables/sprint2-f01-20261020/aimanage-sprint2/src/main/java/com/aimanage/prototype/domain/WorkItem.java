package com.aimanage.prototype.domain;
import jakarta.persistence.*;
@Entity @Table(name="backlog_item")
public class WorkItem {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private Project project;
 @Column(nullable=false,length=20) private String type;
 @Column(nullable=false,length=120) private String title;
 @Column(length=2000) private String description;
 @Column(length=2000) private String acceptance;
 @Enumerated(EnumType.STRING) private Priority priority;
 @ManyToOne private WorkItem requirement;
 protected WorkItem() {}
 public WorkItem(Project project,String type){this.project=project;this.type=type;}
 public void edit(String title,String description,String acceptance,Priority priority,WorkItem requirement){this.title=title;this.description=description;this.acceptance=acceptance;this.priority=priority;this.requirement=requirement;}
 public Long getId(){return id;} public Project getProject(){return project;} public String getType(){return type;} public String getTitle(){return title;} public String getDescription(){return description;} public String getAcceptance(){return acceptance;} public Priority getPriority(){return priority;} public WorkItem getRequirement(){return requirement;}
}
