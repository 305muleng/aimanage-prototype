package com.aimanage.prototype.controller;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import java.time.LocalDate;
import java.util.*;
@Controller
public class SprintController {
 private final SprintService service; private final ProjectTaskService projects; private final BacklogService backlog;
 public SprintController(SprintService s,ProjectTaskService p,BacklogService b){service=s;projects=p;backlog=b;}
 @GetMapping("/iterations") public String page(@RequestParam(required=false) Long projectId,@RequestParam(required=false) Long sprintId,@RequestParam(defaultValue="false") boolean create,HttpSession s,Model m,RedirectAttributes r){
  Long uid=(Long)s.getAttribute("userId");Role role=(Role)s.getAttribute("role");boolean editable=role==Role.PROJECT_MANAGER;
  var ps=editable?projects.projectsForManager(uid):projects.projectsForDeveloper(uid).stream().map(v->v.project()).toList();
  if(projectId==null&&!ps.isEmpty())projectId=ps.get(0).getId();
  m.addAttribute("projects",ps);m.addAttribute("projectId",projectId);m.addAttribute("editable",editable);m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("roleLabel",role.getLabel());
  if(s.getAttribute("backlogCsrf")==null)s.setAttribute("backlogCsrf",UUID.randomUUID().toString());m.addAttribute("csrf",s.getAttribute("backlogCsrf"));
  try{
   var iterations=projectId==null?List.<Sprint>of():service.list(uid,projectId);m.addAttribute("iterations",iterations);
   Long pid=projectId;
   var stories=pid==null?List.<WorkItem>of():backlog.list(uid,pid);
   var tasks=pid==null?List.<Task>of():editable?projects.projectTasks(uid,pid):projects.myTasks(uid).stream().filter(t->t.getProject().getId().equals(pid)).toList();
   var all= SprintService.summaries(stories,tasks);
   var counts=new HashMap<Long,Long>();for(var row:all)if(row.story().getSprint()!=null)counts.merge(row.story().getSprint().getId(),1L,Long::sum);
   m.addAttribute("storyCounts",counts);
   if(sprintId==null&&!iterations.isEmpty()&&!create)sprintId=iterations.stream().filter(it->it.getStatus()==SprintStatus.IN_PROGRESS).findFirst().orElse(iterations.get(iterations.size()-1)).getId();
   Sprint selected=null;
   if(sprintId!=null){Long id=sprintId;selected=iterations.stream().filter(it->it.getId().equals(id)).findFirst().orElseThrow(()->new BusinessException("迭代不属于当前项目"));}
   m.addAttribute("selected",selected);m.addAttribute("sprintId",sprintId);m.addAttribute("create",editable&&(create||iterations.isEmpty()));
   Long selectedId=sprintId;
   var scope=all.stream().filter(row->row.story().getSprint()!=null&&row.story().getSprint().getId().equals(selectedId)).toList();
   m.addAttribute("scope",scope);m.addAttribute("metrics",SprintService.metrics(scope));
   m.addAttribute("candidates",all.stream().filter(row->row.story().getSprint()==null).toList());
   m.addAttribute("unfinished",scope.stream().filter(row->!row.complete()).toList());
   m.addAttribute("targets",iterations.stream().filter(it->!it.getId().equals(selectedId)&&it.getStatus()!=SprintStatus.COMPLETED).toList());
  }catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());return "redirect:/iterations";}
  return "iterations";
 }
 private void csrf(String token,HttpSession s){if(token.isBlank()||!token.equals(s.getAttribute("backlogCsrf")))throw new BusinessException("请刷新页面后重试");}
 @PostMapping("/iterations") public String save(@RequestParam Long projectId,@RequestParam(required=false) Long id,@RequestParam String name,@RequestParam(defaultValue="") String goal,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{csrf(csrfToken,s);id=service.save((Long)s.getAttribute("userId"),projectId,id,name,goal,startDate,endDate).getId();r.addFlashAttribute("success","迭代已保存，名称与周期已同步到关联视图");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/iterations?projectId="+projectId+(id==null?"&create=true":"&sprintId="+id);
 }
 @PostMapping("/iterations/scope") public String scope(@RequestParam Long projectId,@RequestParam Long sprintId,@RequestParam(defaultValue="") String action,@RequestParam(required=false) List<Long> storyIds,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{csrf(csrfToken,s);if(!List.of("ADD","REMOVE").contains(action))throw new BusinessException("范围操作无效");service.scope((Long)s.getAttribute("userId"),projectId,sprintId,storyIds,action.equals("ADD"));r.addFlashAttribute("success","迭代范围已更新，关联任务分组同步更新；任务日期保持不变");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/iterations?projectId="+projectId+"&sprintId="+sprintId;
 }
 @PostMapping("/iterations/state") public String state(@RequestParam Long projectId,@RequestParam Long sprintId,@RequestParam String action,@RequestParam Map<String,String> params,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{
   csrf(csrfToken,s);Long uid=(Long)s.getAttribute("userId");
   if(action.equals("START"))service.start(uid,projectId,sprintId);
   else if(action.equals("COMPLETE")){
    var decisions=new HashMap<Long,SprintService.Resolution>();
    for(var entry:params.entrySet())if(entry.getKey().startsWith("resolution_")){
     Long id=Long.valueOf(entry.getKey().substring(11));String target=params.get("target_"+id);
     decisions.put(id,new SprintService.Resolution(entry.getValue(),target==null||target.isBlank()?null:Long.valueOf(target)));
    }
    service.complete(uid,projectId,sprintId,decisions);
   }else throw new BusinessException("状态操作无效");
   r.addFlashAttribute("success",action.equals("START")?"迭代已开始":"迭代已完成，未完成故事已按你的选择处理");
  }catch(BusinessException|NumberFormatException e){r.addFlashAttribute("error",e instanceof NumberFormatException?"请选择有效的故事和目标迭代":e.getMessage());}
  return "redirect:/iterations?projectId="+projectId+"&sprintId="+sprintId;
 }
 @PostMapping("/backlog-sprint") public String assign(@RequestParam Long projectId,@RequestParam Long id,@RequestParam(required=false) Long sprintId,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{csrf(csrfToken,s);service.assign((Long)s.getAttribute("userId"),projectId,id,sprintId);r.addFlashAttribute("success","故事所属迭代已更新，关联任务的甘特图分组同步更新；任务日期未自动修改");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/backlog?projectId="+projectId+"#item-"+id;
 }
}
