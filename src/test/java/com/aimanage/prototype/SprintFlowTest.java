package com.aimanage.prototype;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class SprintFlowTest {
 @Autowired SprintService sprints; @Autowired BacklogService backlog; @Autowired ProjectTaskService projects; @Autowired UserRepository users; @Autowired MockMvc mvc;
 @Autowired jakarta.persistence.EntityManager entityManager; @Autowired WorkItemRepository items; @Autowired TaskRepository tasks;
 @Test void associatedDataRemainsReadableAfterThePersistenceSessionIsClosed(){
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"detached graph","");projects.addMember(m.getId(),p.getId(),d.getId());
  var it=sprints.save(m.getId(),p.getId(),null,"Detached iteration","",LocalDate.now(),LocalDate.now());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","detached story","","ok",Priority.HIGH,null);sprints.assign(m.getId(),p.getId(),story.getId(),it.getId());
  var task=projects.createTask(m.getId(),p.getId(),"detached task","",null,d.getId());projects.planTask(m.getId(),task.getId(),Priority.HIGH,story.getId());
  entityManager.flush();entityManager.clear();
  var loadedItems=items.findByProjectIdOrderByIdDesc(p.getId());entityManager.clear();
  assertThat(loadedItems.get(0).getSprint().getLabel()).contains("Detached iteration");
  var loadedTasks=tasks.findByProjectIdOrderByUpdatedAtDesc(p.getId());entityManager.clear();
  assertThat(loadedTasks.get(0).getStory().getTitle()).isEqualTo("detached story");
  assertThat(loadedTasks.get(0).getStory().getSprint().getStatus()).isEqualTo(SprintStatus.NOT_STARTED);
 }
 @Test void bulkScopeIsAtomicAndRejectsForeignOrAlreadyPlannedStories(){
  var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"bulk","");
  var it=sprints.save(m.getId(),p.getId(),null,"Iteration","",LocalDate.now(),LocalDate.now().plusDays(13));
  var a=backlog.save(m.getId(),p.getId(),null,"STORY","a","","ok",Priority.HIGH,null);
  var b=backlog.save(m.getId(),p.getId(),null,"STORY","b","","ok",Priority.LOW,null);
  var other=projects.createProject(m.getId(),"foreign","");
  var foreign=backlog.save(m.getId(),other.getId(),null,"STORY","foreign","","ok",Priority.LOW,null);
  assertThatThrownBy(()->sprints.scope(m.getId(),p.getId(),it.getId(),java.util.List.of(a.getId(),foreign.getId()),true)).isInstanceOf(BusinessException.class);
  assertThat(a.getSprint()).isNull();
  sprints.scope(m.getId(),p.getId(),it.getId(),java.util.List.of(a.getId(),b.getId()),true);
  assertThat(a.getSprint()).isEqualTo(it);assertThat(b.getSprint()).isEqualTo(it);
  assertThatThrownBy(()->sprints.scope(m.getId(),p.getId(),it.getId(),java.util.List.of(a.getId()),true)).isInstanceOf(BusinessException.class);
  sprints.scope(m.getId(),p.getId(),it.getId(),java.util.List.of(a.getId(),b.getId()),false);
  assertThat(a.getSprint()).isNull();assertThat(b.getSprint()).isNull();
 }
 @Test void lifecycleRequiresExplicitDecisionsAndKeepsAnEndRecord() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"lifecycle","");projects.addMember(m.getId(),p.getId(),d.getId());
  var it=sprints.save(m.getId(),p.getId(),null,"Current","",LocalDate.now(),LocalDate.now().plusDays(13));
  var next=sprints.save(m.getId(),p.getId(),null,"Next","",LocalDate.now().plusDays(14),LocalDate.now().plusDays(27));
  var keep=backlog.save(m.getId(),p.getId(),null,"STORY","keep no tasks","","ok",Priority.HIGH,null);
  var back=backlog.save(m.getId(),p.getId(),null,"STORY","return to backlog","","ok",Priority.HIGH,null);
  var move=backlog.save(m.getId(),p.getId(),null,"STORY","move next","","ok",Priority.HIGH,null);
  var done=backlog.save(m.getId(),p.getId(),null,"STORY","finished","","ok",Priority.HIGH,null);
  sprints.scope(m.getId(),p.getId(),it.getId(),java.util.List.of(keep.getId(),back.getId(),move.getId(),done.getId()),true);
  var one=projects.createTask(m.getId(),p.getId(),"overdue task","",LocalDate.now().minusDays(1),d.getId());projects.planTask(m.getId(),one.getId(),Priority.HIGH,back.getId());
  var two=projects.createTask(m.getId(),p.getId(),"moving task","",LocalDate.now().plusDays(1),d.getId());projects.planTask(m.getId(),two.getId(),Priority.HIGH,move.getId());
  var three=projects.createTask(m.getId(),p.getId(),"done task","",LocalDate.now().minusDays(1),d.getId());projects.planTask(m.getId(),three.getId(),Priority.HIGH,done.getId());three.changeStatus(TaskStatus.DONE);
  var metrics=SprintService.metrics(SprintService.summaries(backlog.list(m.getId(),p.getId()),projects.projectTasks(m.getId(),p.getId())));
  assertThat(metrics.stories()).isEqualTo(4);assertThat(metrics.tasks()).isEqualTo(3);assertThat(metrics.done()).isEqualTo(1);assertThat(metrics.overdue()).isEqualTo(1);assertThat(metrics.progress()).isEqualTo(33);
  assertThat(it.getStatus()).isEqualTo(SprintStatus.NOT_STARTED);
  assertThatThrownBy(()->sprints.complete(m.getId(),p.getId(),it.getId(),java.util.Map.of())).isInstanceOf(BusinessException.class);
  sprints.start(m.getId(),p.getId(),it.getId());assertThat(it.getStartedAt()).isNotNull();
  assertThatThrownBy(()->sprints.complete(m.getId(),p.getId(),it.getId(),java.util.Map.of())).isInstanceOf(BusinessException.class);
  assertThat(it.getStatus()).isEqualTo(SprintStatus.IN_PROGRESS);assertThat(back.getSprint()).isEqualTo(it);
  sprints.complete(m.getId(),p.getId(),it.getId(),java.util.Map.of(keep.getId(),new SprintService.Resolution("KEEP",null),back.getId(),new SprintService.Resolution("BACKLOG",null),move.getId(),new SprintService.Resolution("MOVE",next.getId())));
  assertThat(it.getStatus()).isEqualTo(SprintStatus.COMPLETED);assertThat(it.getCompletedAt()).isNotNull();
  assertThat(keep.getSprint()).isEqualTo(it);assertThat(back.getSprint()).isNull();assertThat(move.getSprint()).isEqualTo(next);assertThat(done.getSprint()).isEqualTo(it);
  assertThat(it.getClosureSummary()).contains("keep no tasks","return to backlog","move next","Next");
  assertThatThrownBy(()->sprints.assign(m.getId(),p.getId(),keep.getId(),next.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->sprints.start(m.getId(),p.getId(),it.getId())).isInstanceOf(BusinessException.class);
  var session=new MockHttpSession();session.setAttribute("userId",m.getId());session.setAttribute("role",m.getRole());
  mvc.perform(get("/iterations").session(session).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("结束记录")));
 }
 @Test void stateAndScopeEndpointsRequireCsrfAndManager() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"endpoints","");projects.addMember(m.getId(),p.getId(),d.getId());
  var it=sprints.save(m.getId(),p.getId(),null,"endpoint iteration","",LocalDate.now(),LocalDate.now());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","endpoint story","","ok",Priority.HIGH,null);
  var s=new MockHttpSession();s.setAttribute("userId",m.getId());s.setAttribute("role",m.getRole());
  mvc.perform(get("/iterations").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString())).andExpect(status().isOk());
  mvc.perform(post("/iterations/state").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("action","START")).andExpect(flash().attributeExists("error"));
  assertThat(it.getStatus()).isEqualTo(SprintStatus.NOT_STARTED);
  mvc.perform(post("/iterations/scope").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("action","ADD").param("storyIds",story.getId().toString()).param("csrfToken",s.getAttribute("backlogCsrf").toString())).andExpect(flash().attributeExists("success"));
  mvc.perform(post("/iterations/state").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("action","START").param("csrfToken",s.getAttribute("backlogCsrf").toString())).andExpect(flash().attributeExists("success"));
  mvc.perform(get("/iterations").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("resolution_"+story.getId())));
  mvc.perform(post("/iterations/state").session(s).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("action","COMPLETE").param("resolution_"+story.getId(),"BACKLOG").param("csrfToken",s.getAttribute("backlogCsrf").toString())).andExpect(flash().attributeExists("success"));
  assertThat(it.getStatus()).isEqualTo(SprintStatus.COMPLETED);assertThat(story.getSprint()).isNull();
  var member=new MockHttpSession();member.setAttribute("userId",d.getId());member.setAttribute("role",d.getRole());
  mvc.perform(post("/iterations/state").session(member).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("action","START")).andExpect(redirectedUrl("/login"));
  mvc.perform(get("/iterations").session(member).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString())).andExpect(status().isOk());
 }
 @Test void realIterationMembershipAndReverseLinks() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"sprint test","");projects.addMember(m.getId(),p.getId(),d.getId());
  var it=sprints.save(m.getId(),p.getId(),null,"Release alpha","Goal",LocalDate.now(),LocalDate.now().plusDays(13));
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","Login","","accepted",Priority.HIGH,null);
  var task=projects.createTask(m.getId(),p.getId(),"Implement login","",null,d.getId());projects.planTask(m.getId(),task.getId(),Priority.HIGH,story.getId());
  var session=new MockHttpSession();session.setAttribute("userId",m.getId());session.setAttribute("role",m.getRole());
  mvc.perform(get("/iterations").session(session).param("projectId",p.getId().toString())).andExpect(status().isOk());
  mvc.perform(post("/backlog-sprint").session(session).param("projectId",p.getId().toString()).param("id",story.getId().toString()).param("sprintId",it.getId().toString())).andExpect(flash().attributeExists("error"));
  mvc.perform(post("/backlog-sprint").session(session).param("projectId",p.getId().toString()).param("id",story.getId().toString()).param("sprintId",it.getId().toString()).param("csrfToken",session.getAttribute("backlogCsrf").toString())).andExpect(flash().attributeExists("success"));
  assertThat(story.getSprint().getName()).isEqualTo("Release alpha");
  mvc.perform(get("/backlog").session(session).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Implement login")));
  mvc.perform(get("/sprints").session(session).param("projectId",p.getId().toString()).param("sprintId",it.getId().toString()).param("storyId",story.getId().toString())).andExpect(status().isOk()).andExpect(model().attribute("total",1));
  assertThatThrownBy(()->sprints.assign(d.getId(),p.getId(),story.getId(),it.getId())).isInstanceOf(BusinessException.class);
  var other=projects.createProject(m.getId(),"other iteration","");
  assertThatThrownBy(()->sprints.assign(m.getId(),other.getId(),story.getId(),it.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->sprints.save(m.getId(),p.getId(),null,"invalid","",LocalDate.now(),LocalDate.now().minusDays(1))).isInstanceOf(BusinessException.class);
  sprints.assign(m.getId(),p.getId(),story.getId(),null);assertThat(story.getSprint()).isNull();
 }
}
