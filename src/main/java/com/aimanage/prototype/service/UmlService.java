package com.aimanage.prototype.service;
import com.aimanage.prototype.domain.*;import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.Transactional;import java.util.*;
@Service @Transactional public class UmlService {
 private final UmlViewRepository views;private final UmlNodeRepository nodes;private final UmlEdgeRepository edges;private final ProjectRepository projects;private final UserRepository users;private final ProjectMemberRepository members;private final WorkItemRepository stories;private final TaskRepository tasks;
 public UmlService(UmlViewRepository v,UmlNodeRepository n,UmlEdgeRepository e,ProjectRepository p,UserRepository u,ProjectMemberRepository m,WorkItemRepository s,TaskRepository t){views=v;nodes=n;edges=e;projects=p;users=u;members=m;stories=s;tasks=t;}
 private Project access(Long uid,Long pid,boolean write){var u=users.findById(uid).orElseThrow(()->new BusinessException("账号不存在"));var p=projects.findById(pid).orElseThrow(()->new BusinessException("项目不存在"));
 boolean owner=u.getRole()==Role.PROJECT_MANAGER&&p.getOwner().getId().equals(uid);
 if(!owner&&(write||u.getRole()!=Role.DEVELOPER||!members.existsByProjectIdAndUserId(pid,uid)))throw new BusinessException("无权访问该项目 UML");return p;}
 private UmlView view(Long uid,Long id,boolean write){var v=views.findById(id).orElseThrow(()->new BusinessException("视图不存在"));access(uid,v.getProject().getId(),write);return v;}
 private String text(String value,int max,boolean required){value=value==null?"":value.trim();if(value.length()>max||(required&&value.isBlank()))throw new BusinessException("请填写有效文本（最多"+max+"字符）");return value;}
 public UmlView create(Long uid,Long pid,UmlKind kind,String name){if(kind==null)throw new BusinessException("请选择图类型");return views.save(new UmlView(access(uid,pid,true),kind,text(name,80,true)));}
 public UmlView quick(Long uid,Long pid,UmlKind kind){
  if(kind==null)throw new BusinessException("请选择图类型");
  var v=create(uid,pid,kind,kind.getLabel()+" "+(list(uid,pid).stream().filter(i->i.getKind()==kind).count()+1));
  String type=switch(kind){case CLASS->"CLASS";case USE_CASE->"ACTOR";case SEQUENCE->"PARTICIPANT";case ACTIVITY->"START";};
  String first=switch(kind){case CLASS->"新类";case USE_CASE->"参与者";case SEQUENCE->"用户";case ACTIVITY->"开始";};
  String second=switch(kind){case CLASS->"关联类";case USE_CASE->"新用例";case SEQUENCE->"系统";case ACTIVITY->"新活动";};
  String type2=switch(kind){case CLASS->"CLASS";case USE_CASE->"USECASE";case SEQUENCE->"PARTICIPANT";case ACTIVITY->"ACTIVITY";};
  var a=node(uid,v.getId(),null,type,first,"",null,null);var b=node(uid,v.getId(),null,type2,second,"",null,null);
  move(uid,v.getId(),a.getId(),60,90);move(uid,v.getId(),b.getId(),340,90);edge(uid,v.getId(),a.getId(),b.getId(),kind==UmlKind.SEQUENCE?"请求":"");
  return v;
 }
 @Transactional(readOnly=true) public List<UmlView> list(Long uid,Long pid){access(uid,pid,false);return views.findByProjectIdOrderByIdAsc(pid);}
 public UmlNode node(Long uid,Long vid,Long id,String type,String label,String details,Long sid,Long tid){
 if(vid==null)throw new BusinessException("请选择视图");
 var v=view(uid,vid,true);var allowed=switch(v.getKind()){case CLASS->List.of("CLASS");case USE_CASE->List.of("ACTOR","USECASE");case SEQUENCE->List.of("PARTICIPANT");case ACTIVITY->List.of("ACTIVITY","DECISION","START","END");};
 var n=id==null?new UmlNode(v,type):nodes.findById(id).orElseThrow(()->new BusinessException("节点不存在"));
 if(!n.getView().getId().equals(vid)||!allowed.contains(type))throw new BusinessException("节点类型或归属无效");
 WorkItem story=null;Task task=null;
 if(sid!=null){story=stories.findById(sid).orElseThrow(()->new BusinessException("故事不存在"));if(!story.getType().equals("STORY")||!story.getProject().getId().equals(v.getProject().getId()))throw new BusinessException("只能关联本项目故事");}
 if(tid!=null){task=tasks.findById(tid).orElseThrow(()->new BusinessException("任务不存在"));if(!task.getProject().getId().equals(v.getProject().getId()))throw new BusinessException("只能关联本项目任务");}
 String checkedLabel=text(label,100,true),checkedDetails=text(details,1000,false);
 n.changeType(type);n.edit(checkedLabel,checkedDetails,story,task);
 if(id==null){int count=nodes.findByViewIdOrderByIdAsc(vid).size();n.move(v.getKind()==UmlKind.SEQUENCE?40+count*240:40+(count%4)*240,40+(count/4)*180);}
 return nodes.save(n);
 }
 public void move(Long uid,Long vid,Long nid,int x,int y){view(uid,vid,true);var n=nodes.findById(nid).orElseThrow(()->new BusinessException("节点不存在"));if(!n.getView().getId().equals(vid)||x<0||x>1600||y<0||y>1600)throw new BusinessException("节点位置无效");n.move(x,y);}
 public void edge(Long uid,Long vid,Long from,Long to,String label){if(vid==null||from==null||to==null)throw new BusinessException("请选择视图、起点和终点");var v=view(uid,vid,true);var s=nodes.findById(from).orElseThrow(()->new BusinessException("起点不存在"));var t=nodes.findById(to).orElseThrow(()->new BusinessException("终点不存在"));if(!s.getView().getId().equals(vid)||!t.getView().getId().equals(vid))throw new BusinessException("不能跨视图连线");edges.save(new UmlEdge(v,s,t,text(label,100,false)));}
 public void deleteEdge(Long uid,Long vid,Long id){view(uid,vid,true);var e=edges.findById(id).orElseThrow(()->new BusinessException("连线不存在"));if(!e.getView().getId().equals(vid))throw new BusinessException("连线归属无效");edges.delete(e);}
 public void renameEdge(Long uid,Long vid,Long id,String label){
  if(vid==null||id==null)throw new BusinessException("请选择视图和连线");
  view(uid,vid,true);var e=edges.findById(id).orElseThrow(()->new BusinessException("连线不存在"));
  if(!e.getView().getId().equals(vid))throw new BusinessException("连线归属无效");
  e.rename(text(label,100,false));
 }
 public void deleteView(Long uid,Long pid,Long vid){
  if(vid==null)throw new BusinessException("请选择要删除的视图");
  var v=view(uid,vid,true);
  if(!v.getProject().getId().equals(pid))throw new BusinessException("视图不属于当前项目");
  edges.deleteAll(edges.findByViewIdOrderByIdAsc(vid));edges.flush();
  nodes.deleteAll(nodes.findByViewIdOrderByIdAsc(vid));nodes.flush();
  views.delete(v);views.flush();
 }
 @Transactional(readOnly=true) public Diagram data(Long uid,Long vid){var v=view(uid,vid,false);return new Diagram(v.getId(),v.getProject().getId(),v.getKind(),v.getName(),
 nodes.findByViewIdOrderByIdAsc(vid).stream().map(n->new Node(n.getId(),n.getType(),n.getLabel(),n.getDetails(),n.getX(),n.getY(),n.getStory()==null?null:n.getStory().getId(),n.getTask()==null?null:n.getTask().getId())).toList(),
 edges.findByViewIdOrderByIdAsc(vid).stream().map(e->new Edge(e.getId(),e.getSource().getId(),e.getTarget().getId(),e.getLabel())).toList());}
 public record Node(Long id,String type,String label,String details,int x,int y,Long storyId,Long taskId){}
 public record Edge(Long id,Long source,Long target,String label){}
 public record Diagram(Long id,Long projectId,UmlKind kind,String name,List<Node> nodes,List<Edge> edges){}
}
