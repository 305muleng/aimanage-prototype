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
 private final SprintService sprints; private final com.aimanage.prototype.repository.UmlNodeRepository nodes; private final BacklogService backlog;private final ProjectTaskService projects;
 public BacklogController(BacklogService b,ProjectTaskService p,SprintService sp,com.aimanage.prototype.repository.UmlNodeRepository n){backlog=b;projects=p;sprints=sp;nodes=n;}
 @GetMapping("/backlog") public String page(@RequestParam(required=false) Long projectId,HttpSession s,Model m,RedirectAttributes r){
  Long uid=(Long)s.getAttribute("userId");Role role=(Role)s.getAttribute("role");
  var availableProjects=role==Role.PROJECT_MANAGER?projects.projectsForManager(uid):projects.projectsForDeveloper(uid).stream().map(v->v.project()).toList();
  m.addAttribute("projects",availableProjects);
  if(projectId==null && !availableProjects.isEmpty())projectId=availableProjects.get(0).getId();
  m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("roleLabel",role.getLabel());m.addAttribute("editable",role==Role.PROJECT_MANAGER);m.addAttribute("priorities",Priority.values());
  if(s.getAttribute("backlogCsrf")==null)s.setAttribute("backlogCsrf",java.util.UUID.randomUUID().toString());
  m.addAttribute("csrf",s.getAttribute("backlogCsrf"));
  m.addAttribute("epics",java.util.List.of("EPIC1","EPIC2","EPIC3"));
  m.addAttribute("lanes",java.util.List.of("SPRINT1","SPRINT2","SPRINT3","UNPLANNED"));
  try {if(projectId!=null){var list=backlog.list(uid,projectId);m.addAttribute("items",list);m.addAttribute("requirements",list.stream().filter(i->i.getType().equals("REQUIREMENT")).toList());m.addAttribute("projectId",projectId);}}
  catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());return "redirect:/backlog";}
  if(projectId!=null && m.containsAttribute("items")) {
   @SuppressWarnings("unchecked") var items=(java.util.List<WorkItem>)m.getAttribute("items");
   m.addAttribute("storiesByRequirement",items.stream().filter(i->i.getType().equals("STORY")&&i.getRequirement()!=null).collect(java.util.stream.Collectors.groupingBy(i->i.getRequirement().getId())));
   m.addAttribute("iterations",sprints.list(uid,projectId));
   var linkedTasks=role==Role.PROJECT_MANAGER?projects.projectTasks(uid,projectId):projects.myTasks(uid).stream().filter(t->t.getProject().getId().equals(items.isEmpty()?0L:items.get(0).getProject().getId())).toList();
   m.addAttribute("tasksByStory",linkedTasks.stream().filter(t->t.getStory()!=null).collect(java.util.stream.Collectors.groupingBy(t->t.getStory().getId())));
   var umlByStory=new java.util.HashMap<Long,java.util.LinkedHashMap<Long,UmlView>>();
   for(var node:nodes.findByViewProjectId(projectId)){
    var linked=new java.util.ArrayList<WorkItem>();
    if(node.getStory()!=null)linked.add(node.getStory());
    if(node.getTask()!=null && node.getTask().getStory()!=null)linked.add(node.getTask().getStory());
    for(var story:linked)umlByStory.computeIfAbsent(story.getId(),k->new java.util.LinkedHashMap<>()).put(node.getView().getId(),node.getView());
   }
   m.addAttribute("umlByStory",umlByStory);
   var stories=items.stream().filter(i->i.getType().equals("STORY")).toList();
   var rows=new java.util.ArrayList<MapRow>();
   for(var iteration:sprints.list(uid,projectId)){
    var assigned=stories.stream().filter(i->i.getSprint()!=null && i.getSprint().getId().equals(iteration.getId())).toList();
    if(!assigned.isEmpty())rows.add(new MapRow(iteration.getName(),iteration.getStartDate()+" — "+iteration.getEndDate(),iteration.getStatus().getLabel(),groups(assigned),assigned.size()));
   }
   var unassigned=stories.stream().filter(i->i.getSprint()==null).toList();
   if(!unassigned.isEmpty())rows.add(new MapRow("待规划","","未加入迭代",groups(unassigned),unassigned.size()));
   m.addAttribute("mapRows",rows);m.addAttribute("storyCount",stories.size());
  }
  return "backlog";
 }
 @PostMapping("/backlog") public String save(@RequestParam Long projectId,@RequestParam(required=false) Long id,@RequestParam String type,@RequestParam String title,@RequestParam(defaultValue="") String description,@RequestParam(defaultValue="") String acceptance,@RequestParam Priority priority,@RequestParam(required=false) Long requirementId,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{if(csrfToken.isBlank()||!csrfToken.equals(s.getAttribute("backlogCsrf")))throw new BusinessException("请刷新页面后重试");id=backlog.save((Long)s.getAttribute("userId"),projectId,id,type,title,description,acceptance,priority,requirementId).getId();r.addFlashAttribute("success","需求／用户故事已保存；关联视图重新打开后显示最新内容，任务计划日期不自动修改");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/backlog?projectId="+projectId+(id==null?"#new-item":"#item-"+id);
 }
 @PostMapping("/backlog-map") public String place(@RequestParam Long projectId,@RequestParam Long id,@RequestParam String epic,@RequestParam String lane,@RequestParam(defaultValue="") String feature,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{if(csrfToken.isBlank()||!csrfToken.equals(s.getAttribute("backlogCsrf")))throw new BusinessException("请刷新页面后重试");backlog.place((Long)s.getAttribute("userId"),projectId,id,epic,lane,feature);r.addFlashAttribute("success","故事地图位置已保存");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/backlog?projectId="+projectId+"#story-map";
 }
 private static java.util.List<MapGroup> groups(java.util.List<WorkItem> stories){
  var grouped=new java.util.LinkedHashMap<Long,java.util.List<WorkItem>>();
  for(var story:stories)grouped.computeIfAbsent(story.getRequirement()==null?0L:story.getRequirement().getId(),k->new java.util.ArrayList<>()).add(story);
  return grouped.values().stream().map(list->new MapGroup(list.get(0).getRequirement()==null?"未关联需求":list.get(0).getRequirement().getTitle(),list)).toList();
 }
 public record MapGroup(String title,java.util.List<WorkItem> stories){}
 public record MapRow(String label,String cycle,String status,java.util.List<MapGroup> groups,int count){}
}
