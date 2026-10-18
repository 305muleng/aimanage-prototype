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
public class GanttController {
 private final ProjectTaskService service; private final SprintService sprints;
 public GanttController(ProjectTaskService service,SprintService sprints){this.service=service;this.sprints=sprints;}
 @GetMapping("/sprints") public String page(@RequestParam(required=false) Long projectId,@RequestParam(defaultValue="ALL") String lane,@RequestParam(required=false) Long sprintId,@RequestParam(required=false) Long storyId,
   @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,HttpSession s,Model m,RedirectAttributes redirect){
  Long uid=(Long)s.getAttribute("userId");Role role=(Role)s.getAttribute("role");
  boolean manager=role==Role.PROJECT_MANAGER;
  m.addAttribute("lanes",List.of("SPRINT1","SPRINT2","SPRINT3","UNPLANNED"));
  var accessible=manager?service.projectsForManager(uid):service.projectsForDeveloper(uid).stream().map(v->v.project()).toList();
  m.addAttribute("projects",accessible);m.addAttribute("displayName",s.getAttribute("displayName"));m.addAttribute("roleLabel",role.getLabel());m.addAttribute("editable",manager);
  if(projectId==null&&!accessible.isEmpty())projectId=accessible.get(0).getId();
  if(!List.of("ALL","SPRINT1","SPRINT2","SPRINT3","UNPLANNED").contains(lane))lane="ALL";
  List<Task> tasks=List.of();
  if(projectId!=null){
   Long pid=projectId;
   if(accessible.stream().noneMatch(p->p.getId().equals(pid))){redirect.addFlashAttribute("error","无权访问该项目");return "redirect:/sprints";}
   tasks=manager?service.projectTasks(uid,pid):service.myTasks(uid).stream().filter(t->t.getProject().getId().equals(pid)).toList();
   var iterations=sprints.list(uid,pid);m.addAttribute("iterations",iterations);
   if(sprintId!=null&&iterations.stream().noneMatch(it->it.getId().equals(sprintId))){redirect.addFlashAttribute("error","迭代不属于当前项目");return "redirect:/sprints?projectId="+pid;}
   tasks=tasks.stream().filter(t->sprintId==null||(t.getStory()!=null&&t.getStory().getSprint()!=null&&t.getStory().getSprint().getId().equals(sprintId))).filter(t->storyId==null||(t.getStory()!=null&&t.getStory().getId().equals(storyId))).toList();
   if(from==null&&sprintId!=null)from=iterations.stream().filter(it->it.getId().equals(sprintId)).findFirst().orElseThrow().getStartDate();
   String selectedLane=lane;
   tasks=tasks.stream().filter(t->selectedLane.equals("ALL")||selectedLane.equals(t.getStory()==null?"UNPLANNED":t.getStory().getMapLane())).toList();
  }
  if(from==null)from=tasks.stream().map(t->t.getStartDate()!=null?t.getStartDate():t.getDueDate()).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(LocalDate.now());
  final LocalDate start=from;
  var days=java.util.stream.IntStream.range(0,28).mapToObj(start::plusDays).toList();
  var rows=new ArrayList<Row>();
  for(var t:tasks){boolean valid=t.getStartDate()!=null&&t.getDueDate()!=null&&!t.getDueDate().isBefore(t.getStartDate());
   rows.add(new Row(t,valid,days.stream().map(d->valid&&!d.isBefore(t.getStartDate())&&!d.isAfter(t.getDueDate())).toList()));
  }
  long done=tasks.stream().filter(t->t.getStatus()==TaskStatus.DONE).count();
  m.addAttribute("sprintId",sprintId);m.addAttribute("storyId",storyId);m.addAttribute("rows",rows);m.addAttribute("days",days);m.addAttribute("today",LocalDate.now());m.addAttribute("total",tasks.size());m.addAttribute("done",done);
  m.addAttribute("todo",tasks.stream().filter(t->t.getStatus()==TaskStatus.TODO).count());m.addAttribute("doing",tasks.stream().filter(t->t.getStatus()==TaskStatus.IN_PROGRESS).count());
  m.addAttribute("overdue",tasks.stream().filter(t->t.getStatus()!=TaskStatus.DONE&&t.getDueDate()!=null&&t.getDueDate().isBefore(LocalDate.now())).count());
  m.addAttribute("progress",tasks.isEmpty()?0:Math.round(done*100.0/tasks.size()));
  m.addAttribute("projectId",projectId);m.addAttribute("lane",lane);m.addAttribute("from",from);m.addAttribute("prev",from.minusDays(28));m.addAttribute("next",from.plusDays(28));
  if(s.getAttribute("ganttCsrf")==null)s.setAttribute("ganttCsrf",UUID.randomUUID().toString());m.addAttribute("csrf",s.getAttribute("ganttCsrf"));
  return "gantt";
 }
 @PostMapping("/sprints") public String schedule(@RequestParam Long projectId,@RequestParam Long taskId,
  @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
  @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate dueDate,
  @RequestParam(defaultValue="") String csrfToken,HttpSession s,RedirectAttributes r){
  try{if(csrfToken.isBlank()||!csrfToken.equals(s.getAttribute("ganttCsrf")))throw new BusinessException("请刷新页面后重试");
   Long uid=(Long)s.getAttribute("userId");
   if(service.projectTasks(uid,projectId).stream().noneMatch(t->t.getId().equals(taskId)))throw new BusinessException("任务不在当前项目");
   service.scheduleTask(uid,taskId,startDate,dueDate);r.addFlashAttribute("success","任务计划日期已保存");
  }catch(BusinessException e){r.addFlashAttribute("error",e.getMessage());}
  return "redirect:/sprints?projectId="+projectId;
 }
 public record Row(Task task,boolean scheduled,List<Boolean> cells){}
}
