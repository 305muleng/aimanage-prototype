package com.aimanage.prototype.controller;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
@Controller
public class BacklogController {
 private final BacklogService backlog;private final ProjectTaskService projects;
 public BacklogController(BacklogService b,ProjectTaskService p){backlog=b;projects=p;}
 @GetMapping("/backlog") public String page(@RequestParam(required=false) Long projectId,HttpSession s,Model m,RedirectAttributes r){
  Long uid=(Long)s.getAttribute("userId");Role role=(Role)s.getAttribute("role");
  m.addAttribute("projects",role==Role.PROJECT_MANAGER?projects.projectsForManager(uid):projects.projectsForDeveloper(uid).stream().map(v->v.project()).toList());
  m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("roleLabel",role.getLabel());m.addAttribute("editable",role==Role.PROJECT_MANAGER);m.addAttribute("priorities",Priority.values());
  if(s.getAttribute("backlogCsrf")==null)s.setAttribute("backlogCsrf",java.util.UUID.randomUUID().toString());
  m.addAttribute("csrf",s.getAttribute("backlogCsrf"));
  try {if(projectId!=null){var list=backlog.list(uid,projectId);m.addAttribute("items",list);m.addAttribute("requirements",list.stream().filter(i->i.getType().equals("REQUIREMENT")).toList());m.addAttribute("projectId",projectId);}}
  catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());return "redirect:/backlog";}
  return "backlog";
 }
 @PostMapping("/backlog") public String save(@RequestParam Long projectId,@RequestParam(required=false) Long id,@RequestParam String type,@RequestParam String title,@RequestParam(defaultValue="") String description,@RequestParam(defaultValue="") String acceptance,@RequestParam Priority priority,@RequestParam(required=false) Long requirementId,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{if(csrfToken.isBlank()||!csrfToken.equals(s.getAttribute("backlogCsrf")))throw new BusinessException("请刷新页面后重试");backlog.save((Long)s.getAttribute("userId"),projectId,id,type,title,description,acceptance,priority,requirementId);r.addFlashAttribute("success","需求／用户故事已保存");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/backlog?projectId="+projectId;
 }
}
