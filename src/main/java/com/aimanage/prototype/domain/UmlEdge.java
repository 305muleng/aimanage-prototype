package com.aimanage.prototype.domain;
import jakarta.persistence.*;
@Entity public class UmlEdge {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
 @ManyToOne(optional=false) private UmlView view;
 @ManyToOne(optional=false) private UmlNode source;
 @ManyToOne(optional=false) private UmlNode target;
 @Column(length=100) private String label;
 protected UmlEdge(){}
 public UmlEdge(UmlView v,UmlNode s,UmlNode t,String l){view=v;source=s;target=t;label=l;}
 public void rename(String value){label=value;}
 public Long getId(){return id;}public UmlView getView(){return view;}public UmlNode getSource(){return source;}public UmlNode getTarget(){return target;}public String getLabel(){return label;}
}
