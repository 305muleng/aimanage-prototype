package com.aimanage.prototype.domain;
import jakarta.persistence.*;
@Entity public class UmlView {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private Project project;
 @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private UmlKind kind;
 @Column(nullable=false,length=80) private String name;
 protected UmlView(){}
 public UmlView(Project p,UmlKind k,String n){project=p;kind=k;name=n;}
 public Long getId(){return id;} public Project getProject(){return project;} public UmlKind getKind(){return kind;} public String getName(){return name;}
}
