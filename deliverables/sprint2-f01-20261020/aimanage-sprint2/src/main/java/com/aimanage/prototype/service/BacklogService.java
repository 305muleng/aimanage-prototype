package com.aimanage.prototype.service;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service @Transactional
public class BacklogService {
 private final WorkItemRepository items; private final ProjectRepository projects; private final UserRepository users; private final ProjectMemberRepository members;
 public BacklogService(WorkItemRepository i,ProjectRepository p,UserRepository u,ProjectMemberRepository m){items=i;projects=p;users=u;members=m;}
 private Project access(Long userId,Long projectId,boolean write){
  var u=users.findById(userId).orElseThrow(()->new BusinessException("账号不存在"));
  var p=projects.findById(projectId).orElseThrow(()->new BusinessException("项目不存在"));
  boolean owner=u.getRole()==Role.PROJECT_MANAGER && p.getOwner().getId().equals(userId);
  if(!owner && (write || u.getRole()!=Role.DEVELOPER || !members.existsByProjectIdAndUserId(projectId,userId))) throw new BusinessException("无权访问该项目需求");
  return p;
 }
 @Transactional(readOnly=true) public List<WorkItem> list(Long uid,Long pid){access(uid,pid,false);return items.findByProjectIdOrderByIdDesc(pid);}
 public WorkItem save(Long uid,Long pid,Long id,String type,String title,String description,String acceptance,Priority priority,Long requirementId){
  var p=access(uid,pid,true);
  if(!"REQUIREMENT".equals(type)&&!"STORY".equals(type))throw new BusinessException("类型无效");
  title=title==null?"":title.trim();description=description==null?"":description.trim();acceptance=acceptance==null?"":acceptance.trim();
  if(title.isBlank()||title.length()>120||description.length()>2000||acceptance.length()>2000||priority==null)throw new BusinessException("标题需为1–120字符，说明和验收标准最多2000字符，请选择优先级");
  if(type.equals("STORY")&&acceptance.isBlank())throw new BusinessException("用户故事必须填写验收标准");
  var item=id==null?new WorkItem(p,type):items.findById(id).orElseThrow(()->new BusinessException("条目不存在"));
  if(!item.getProject().getId().equals(pid)||!item.getType().equals(type))throw new BusinessException("不能修改其他项目条目或改变类型");
  WorkItem requirement=null;
  if(requirementId!=null){requirement=items.findById(requirementId).orElseThrow(()->new BusinessException("需求不存在"));if(!type.equals("STORY")||!requirement.getType().equals("REQUIREMENT")||!requirement.getProject().getId().equals(pid))throw new BusinessException("只能关联当前项目的需求");}
  item.edit(title,description,acceptance,priority,requirement);return items.save(item);
 }
}
