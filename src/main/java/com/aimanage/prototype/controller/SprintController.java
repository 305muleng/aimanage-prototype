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
@Controller
public class SprintController {
 private final SprintService service; private final ProjectTaskService projects; private final BacklogService backlog;
 public SprintController(SprintService s,ProjectTaskService p,BacklogService b){service=s;projects=p;backlog=b;}
 @GetMapping("/iterations") public String page(@RequestParam(required=false) Long projectId,HttpSession s,Model m){
  Long uid=(Long)s.getAttribute("userId");Role role=(Role)s.getAttribute("role");
  var ps=role==Role.PROJECT_MANAGER?projects.projectsForManager(uid):projects.projectsForDeveloper(uid).stream().map(v->v.project()).toList();
  if(projectId==null&&!ps.isEmpty())projectId=ps.get(0).getId();
  m.addAttribute("projects",ps);m.addAttribute("projectId",projectId);m.addAttribute("editable",role==Role.PROJECT_MANAGER);m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("roleLabel",role.getLabel());
  if(s.getAttribute("backlogCsrf")==null)s.setAttribute("backlogCsrf",java.util.UUID.randomUUID().toString());m.addAttribute("csrf",s.getAttribute("backlogCsrf"));
  m.addAttribute("iterations",projectId==null?java.util.List.of():service.list(uid,projectId));
  m.addAttribute("stories",projectId==null?java.util.List.of():backlog.list(uid,projectId).stream().filter(i->i.getType().equals("STORY")).toList());
  return "iterations";
 }
 private void csrf(String token,HttpSession s){if(token.isBlank()||!token.equals(s.getAttribute("backlogCsrf")))throw new BusinessException("请刷新页面后重试");}
 @PostMapping("/iterations") public String save(@RequestParam Long projectId,@RequestParam(required=false) Long id,@RequestParam String name,@RequestParam(defaultValue="") String goal,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate endDate,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{csrf(csrfToken,s);service.save((Long)s.getAttribute("userId"),projectId,id,name,goal,startDate,endDate);r.addFlashAttribute("success","迭代已保存；故事和甘特图将使用最新名称与周期");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/iterations?projectId="+projectId;
 }
 @PostMapping("/backlog-sprint") public String assign(@RequestParam Long projectId,@RequestParam Long id,@RequestParam(required=false) Long sprintId,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{csrf(csrfToken,s);service.assign((Long)s.getAttribute("userId"),projectId,id,sprintId);r.addFlashAttribute("success","故事所属迭代已更新，关联任务的甘特图分组同步更新；任务日期未自动修改");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/backlog?projectId="+projectId+"#item-"+id;
 }
}
