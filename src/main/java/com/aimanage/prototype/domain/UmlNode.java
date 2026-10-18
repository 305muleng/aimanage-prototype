package com.aimanage.prototype.domain;
import jakarta.persistence.*;
@Entity public class UmlNode {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private UmlView view;
 @Column(length=20,nullable=false) private String type;
 @Column(length=100,nullable=false) private String label;
 @Column(length=1000) private String details;
 private int x; private int y;
 @ManyToOne private WorkItem story; @ManyToOne private Task task;
 protected UmlNode(){}
 public UmlNode(UmlView v,String t){view=v;type=t;}
 public void edit(String l,String d,WorkItem s,Task t){label=l;details=d;story=s;task=t;}
 public void move(int x,int y){this.x=x;this.y=y;}
 public void changeType(String type){this.type=type;}
 public Long getId(){return id;}public UmlView getView(){return view;}public String getType(){return type;}public String getLabel(){return label;}public String getDetails(){return details;}public int getX(){return x;}public int getY(){return y;}public WorkItem getStory(){return story;}public Task getTask(){return task;}
}
