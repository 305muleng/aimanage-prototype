package com.aimanage.prototype.controller;
import com.aimanage.prototype.domain.*;import com.aimanage.prototype.service.*;
import org.springframework.stereotype.Controller;import org.springframework.ui.Model;import org.springframework.web.bind.annotation.*;import org.springframework.web.servlet.mvc.support.RedirectAttributes;import jakarta.servlet.http.HttpSession;
@Controller public class UmlController {
 private final UmlService uml;private final ProjectTaskService projects;private final BacklogService backlog;
 public UmlController(UmlService u,ProjectTaskService p,BacklogService b){uml=u;projects=p;backlog=b;}
 private Long uid(HttpSession s){return (Long)s.getAttribute("userId");}
 private void token(HttpSession s,String t){if(t.isBlank()||!t.equals(s.getAttribute("umlCsrf")))throw new BusinessException("请刷新页面后重试");}
 @GetMapping("/uml") public String page(@RequestParam(required=false) Long projectId,@RequestParam(required=false) Long viewId,HttpSession s,Model m,RedirectAttributes r){
 var role=(Role)s.getAttribute("role");boolean edit=role==Role.PROJECT_MANAGER;var ps=edit?projects.projectsForManager(uid(s)):projects.projectsForDeveloper(uid(s)).stream().map(v->v.project()).toList();
 m.addAttribute("projects",ps);m.addAttribute("editable",edit);m.addAttribute("roleLabel",role.getLabel());m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("kinds",UmlKind.values());
 if(s.getAttribute("umlCsrf")==null)s.setAttribute("umlCsrf",java.util.UUID.randomUUID().toString());m.addAttribute("csrf",s.getAttribute("umlCsrf"));
 if(projectId==null&&!ps.isEmpty())projectId=ps.get(0).getId();
 try {if(projectId!=null){var vs=uml.list(uid(s),projectId);m.addAttribute("views",vs);m.addAttribute("projectId",projectId);
 if(viewId==null&&!vs.isEmpty())viewId=vs.get(0).getId();
 if(viewId!=null){var data=uml.data(uid(s),viewId);if(!data.projectId().equals(projectId))throw new BusinessException("视图不属于当前项目");m.addAttribute("diagram",data);m.addAttribute("viewId",viewId);}
 var storyList=backlog.list(uid(s),projectId).stream().filter(i->i.getType().equals("STORY")).toList();
 var taskList=edit?projects.projectTasks(uid(s),projectId):projects.myTasks(uid(s)).stream().filter(t->t.getProject().getId().equals((Long)m.getAttribute("projectId"))).toList();
 m.addAttribute("stories",storyList);m.addAttribute("tasks",taskList);
 if(m.getAttribute("diagram") instanceof UmlService.Diagram current){
  var storyIds=current.nodes().stream().map(UmlService.Node::storyId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
  var taskIds=current.nodes().stream().map(UmlService.Node::taskId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
  var linkedStories=storyList.stream().filter(i->storyIds.contains(i.getId())).toList();
  var linkedTasks=taskList.stream().filter(t->taskIds.contains(t.getId())).toList();
  m.addAttribute("linkedStories",linkedStories);m.addAttribute("linkedTasks",linkedTasks);
  m.addAttribute("restrictedLinkedTasks",taskIds.size()>linkedTasks.size());
 }
 }}
 catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());return "redirect:/uml";}return "uml";
 }
 @GetMapping("/uml/data") @ResponseBody public UmlService.Diagram data(@RequestParam Long viewId,HttpSession s){return uml.data(uid(s),viewId);}
 @PostMapping("/uml") public String change(@RequestParam String action,@RequestParam Long projectId,@RequestParam(required=false) Long viewId,
 @RequestParam(required=false) Long id,@RequestParam(defaultValue="") String name,@RequestParam(required=false) UmlKind kind,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String label,@RequestParam(defaultValue="") String details,
 @RequestParam(required=false) Long storyId,@RequestParam(required=false) Long taskId,@RequestParam(required=false) Long source,@RequestParam(required=false) Long target,@RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
 try{token(s,csrfToken);switch(action){case "quick"->viewId=uml.quick(uid(s),projectId,kind).getId();case "view"->viewId=uml.create(uid(s),projectId,kind,name).getId();case "node"->uml.node(uid(s),viewId,id,type,label,details,storyId,taskId);case "edge"->uml.edge(uid(s),viewId,source,target,label);case "renameEdge"->uml.renameEdge(uid(s),viewId,id,label);case "deleteEdge"->uml.deleteEdge(uid(s),viewId,id);case "deleteView"->{uml.deleteView(uid(s),projectId,viewId);viewId=null;}default->throw new BusinessException("操作无效");}r.addFlashAttribute("success",action.equals("deleteView")?"UML 图已删除":"UML 已保存");}catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
 return "redirect:/uml?projectId="+projectId+(viewId==null?"":"&viewId="+viewId);
 }
 @PostMapping("/uml/move") @ResponseBody public java.util.Map<String,String> move(@RequestParam Long viewId,@RequestParam Long id,@RequestParam int x,@RequestParam int y,@RequestParam(defaultValue="") String csrfToken,HttpSession s){token(s,csrfToken);uml.move(uid(s),viewId,id,x,y);return java.util.Map.of("status","saved");}
}
