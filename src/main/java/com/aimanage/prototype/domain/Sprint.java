package com.aimanage.prototype.domain;
import jakarta.persistence.*;
import java.time.LocalDate;
@Entity @Table(name="project_sprint")
public class Sprint {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private Project project;
 @Column(nullable=false,length=120) private String name;
 @Column(length=2000) private String goal;
 private LocalDate startDate;
 private LocalDate endDate;
 protected Sprint(){}
 public Sprint(Project project){this.project=project;}
 public void edit(String name,String goal,LocalDate start,LocalDate end){this.name=name;this.goal=goal;startDate=start;endDate=end;}
 public Long getId(){return id;} public Project getProject(){return project;} public String getName(){return name;} public String getGoal(){return goal;} public LocalDate getStartDate(){return startDate;} public LocalDate getEndDate(){return endDate;}
 public String getLabel(){return name+" · "+startDate+" — "+endDate;}
}
