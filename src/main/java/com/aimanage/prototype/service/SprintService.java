package com.aimanage.prototype.service;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.List;
@Service @Transactional
public class SprintService {
 private final SprintRepository sprints; private final BacklogService backlog; private final ProjectRepository projects; private final WorkItemRepository items;
 public SprintService(SprintRepository s,BacklogService b,ProjectRepository p,WorkItemRepository i){sprints=s;backlog=b;projects=p;items=i;}
 @Transactional(readOnly=true) public List<Sprint> list(Long uid,Long pid){backlog.list(uid,pid);return sprints.findByProjectIdOrderByStartDateAsc(pid);}
 private Project owner(Long uid,Long pid){var p=projects.findById(pid).orElseThrow(()->new BusinessException("项目不存在"));if(!p.getOwner().getId().equals(uid))throw new BusinessException("仅项目负责人可修改迭代");return p;}
 public Sprint save(Long uid,Long pid,Long id,String name,String goal,LocalDate start,LocalDate end){
  var p=owner(uid,pid);name=name==null?"":name.trim();goal=goal==null?"":goal.trim();
  if(name.isBlank()||name.length()>120||goal.length()>2000||start==null||end==null||end.isBefore(start))throw new BusinessException("请填写迭代名称和有效周期，结束日期不能早于开始日期");
  var s=id==null?new Sprint(p):sprints.findById(id).orElseThrow(()->new BusinessException("迭代不存在"));
  if(!s.getProject().getId().equals(pid))throw new BusinessException("迭代不属于当前项目");
  s.edit(name,goal,start,end);return sprints.save(s);
 }
 public void assign(Long uid,Long pid,Long itemId,Long sprintId){
  owner(uid,pid);var i=items.findById(itemId).orElseThrow(()->new BusinessException("故事不存在"));
  if(!i.getProject().getId().equals(pid)||!i.getType().equals("STORY"))throw new BusinessException("只能调整当前项目的用户故事");
  Sprint s=sprintId==null?null:sprints.findById(sprintId).orElseThrow(()->new BusinessException("迭代不存在"));
  if(s!=null&&!s.getProject().getId().equals(pid))throw new BusinessException("不能加入其他项目的迭代");
  i.assignSprint(s);
 }
}
