package com.aimanage.prototype.service;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service @Transactional
public class SprintService {
 private final SprintRepository sprints; private final BacklogService backlog; private final WorkItemRepository items; private final TaskRepository tasks; private final UserRepository users;
 public SprintService(SprintRepository s,BacklogService b,WorkItemRepository i,TaskRepository t,UserRepository u){sprints=s;backlog=b;items=i;tasks=t;users=u;}
 @Transactional(readOnly=true) public List<Sprint> list(Long uid,Long pid){backlog.list(uid,pid);return sprints.findByProjectIdOrderByStartDateAsc(pid);}
 private Project owner(Long uid,Long pid){
  var p=sprints.lockProject(pid).orElseThrow(()->new BusinessException("项目不存在"));
  var u=users.findById(uid).orElseThrow(()->new BusinessException("账号不存在"));
  if(u.getRole()!=Role.PROJECT_MANAGER||!p.getOwner().getId().equals(uid))throw new BusinessException("仅项目负责人可修改迭代");
  return p;
 }
 private Sprint iteration(Long pid,Long id){
  var it=sprints.findById(id).orElseThrow(()->new BusinessException("迭代不存在"));
  if(!it.getProject().getId().equals(pid))throw new BusinessException("迭代不属于当前项目");return it;
 }
 private void mutable(Sprint it){if(it.getStatus()==SprintStatus.COMPLETED)throw new BusinessException("已完成迭代不能修改资料或范围");}
 public Sprint save(Long uid,Long pid,Long id,String name,String goal,LocalDate start,LocalDate end){
  var p=owner(uid,pid);name=name==null?"":name.trim();goal=goal==null?"":goal.trim();
  if(name.isBlank()||name.length()>120||goal.length()>2000||start==null||end==null||end.isBefore(start))throw new BusinessException("请填写迭代名称和有效周期，结束日期不能早于开始日期");
  var it=id==null?new Sprint(p):iteration(pid,id);mutable(it);
  it.edit(name,goal,start,end);return sprints.save(it);
 }
 public void assign(Long uid,Long pid,Long itemId,Long sprintId){
  owner(uid,pid);var item=story(pid,itemId);
  if(item.getSprint()!=null)mutable(item.getSprint());
  Sprint target=sprintId==null?null:iteration(pid,sprintId);if(target!=null)mutable(target);
  item.assignSprint(target);
 }
 private WorkItem story(Long pid,Long id){
  var i=items.findById(id).orElseThrow(()->new BusinessException("故事不存在"));
  if(!i.getProject().getId().equals(pid)||!i.getType().equals("STORY"))throw new BusinessException("只能调整当前项目的用户故事");return i;
 }
 public void scope(Long uid,Long pid,Long sprintId,List<Long> ids,boolean add){
  owner(uid,pid);var it=iteration(pid,sprintId);mutable(it);
  if(ids==null||ids.isEmpty())throw new BusinessException("请先选择用户故事");
  var selected=ids.stream().distinct().map(id->story(pid,id)).toList();
  for(var i:selected){
   if(add&&i.getSprint()!=null)throw new BusinessException("所选故事已加入迭代，请刷新后重试");
   if(!add&&(i.getSprint()==null||!i.getSprint().getId().equals(sprintId)))throw new BusinessException("所选故事不属于当前迭代，请刷新后重试");
  }
  selected.forEach(i->i.assignSprint(add?it:null));
 }
 public void start(Long uid,Long pid,Long id){
  owner(uid,pid);var it=iteration(pid,id);
  if(it.getStatus()!=SprintStatus.NOT_STARTED)throw new BusinessException("只有未开始的迭代可以开始");
  it.start();
 }
 public record Resolution(String action,Long targetId){}
 public void complete(Long uid,Long pid,Long id,Map<Long,Resolution> decisions){
  owner(uid,pid);var it=iteration(pid,id);
  if(it.getStatus()!=SprintStatus.IN_PROGRESS)throw new BusinessException("只有进行中的迭代可以完成");
  var summaries=summaries(items.findByProjectIdOrderByIdDesc(pid),tasks.findByProjectIdOrderByUpdatedAtDesc(pid));
  var unfinished=summaries.stream().filter(row->row.story().getSprint()!=null&&row.story().getSprint().getId().equals(id)&&!row.complete()).toList();
  var targetMap=new HashMap<Long,Sprint>();
  // Validate every decision before changing membership; stale forms cannot silently close new unfinished stories.
  for(var row:unfinished){
   var decision=decisions.get(row.story().getId());
   if(decision==null||decision.action()==null||!List.of("KEEP","BACKLOG","MOVE").contains(decision.action()))throw new BusinessException("请为每个未完成故事明确选择处理方式（无任务故事也需确认）");
   if(decision.action().equals("MOVE")){
    if(decision.targetId()==null||decision.targetId().equals(id))throw new BusinessException("请选择其他未完成的目标迭代");
    var target=iteration(pid,decision.targetId());mutable(target);targetMap.put(row.story().getId(),target);
   }
  }
  var summary=new StringBuilder("结束时逐条确认未完成故事，共 "+unfinished.size()+" 个。");
  for(var row:unfinished){
   var story=row.story();var d=decisions.get(story.getId());
   switch(d.action()){
    case "BACKLOG" -> {story.assignSprint(null);summary.append("\nUS-").append(story.getId()).append(" ").append(story.getTitle()).append(" → 移回 Backlog");}
    case "MOVE" -> {var target=targetMap.get(story.getId());story.assignSprint(target);summary.append("\nUS-").append(story.getId()).append(" ").append(story.getTitle()).append(" → ").append(target.getLabel());}
    default -> summary.append("\nUS-").append(story.getId()).append(" ").append(story.getTitle()).append(" → 留在本迭代（未完成）");
   }
  }
  it.finish(summary.toString());
 }
 public record StorySummary(WorkItem story,List<Task> tasks,boolean complete){}
 public static List<StorySummary> summaries(List<WorkItem> stories,List<Task> tasks){
  var byStory=tasks.stream().filter(t->t.getStory()!=null).collect(Collectors.groupingBy(t->t.getStory().getId()));
  return stories.stream().filter(i->i.getType().equals("STORY")).map(i->{var linked=byStory.getOrDefault(i.getId(),List.of());return new StorySummary(i,linked,!linked.isEmpty()&&linked.stream().allMatch(t->t.getStatus()==TaskStatus.DONE));}).toList();
 }
 public record Metrics(int stories,int tasks,long done,long overdue,int progress){}
 public static Metrics metrics(List<StorySummary> scope){
  var tasks=scope.stream().flatMap(row->row.tasks().stream()).toList();
  long done=tasks.stream().filter(t->t.getStatus()==TaskStatus.DONE).count();
  long overdue=tasks.stream().filter(t->t.getStatus()!=TaskStatus.DONE&&t.getDueDate()!=null&&t.getDueDate().isBefore(LocalDate.now())).count();
  return new Metrics(scope.size(),tasks.size(),done,overdue,tasks.isEmpty()?0:(int)Math.round(done*100.0/tasks.size()));
 }
}
