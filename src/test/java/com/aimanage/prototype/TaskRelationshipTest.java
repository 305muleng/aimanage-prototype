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
class TaskRelationshipTest {
 @Autowired ProjectTaskService projects; @Autowired BacklogService backlog; @Autowired SprintService sprints;
 @Autowired UserRepository users; @Autowired MockMvc mvc;
 @Test void taskWorkspaceRendersRealRelationshipsAndClosureRejectsReassignment() throws Exception {
  var manager=users.findByUsername("manager").orElseThrow();var dev=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(manager.getId(),"relationship check","");projects.addMember(manager.getId(),p.getId(),dev.getId());
  var req=backlog.save(manager.getId(),p.getId(),null,"REQUIREMENT","real requirement","","",Priority.HIGH,null);
  var story=backlog.save(manager.getId(),p.getId(),null,"STORY","real story","","accepted",Priority.HIGH,req.getId());
  var sprint=sprints.save(manager.getId(),p.getId(),null,"real sprint","",LocalDate.now(),LocalDate.now().plusDays(5));
  sprints.assign(manager.getId(),p.getId(),story.getId(),sprint.getId());
  var task=projects.savePlannedTask(manager.getId(),p.getId(),null,"real task","",null,dev.getId(),Priority.HIGH,story.getId());
  assertThat(task.getStory().getSprint().getId()).isEqualTo(sprint.getId());
  var session=new MockHttpSession();session.setAttribute("userId",manager.getId());session.setAttribute("role",Role.PROJECT_MANAGER);session.setAttribute("displayName","manager");
  var html=mvc.perform(get("/manager/tasks").param("projectId",p.getId().toString()).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(html).contains("real requirement","real story","real sprint","real task");
  mvc.perform(post("/manager/tasks/save").session(session).param("projectId",p.getId().toString()).param("title","no csrf").param("assigneeId",dev.getId().toString()).param("priority","HIGH")).andExpect(flash().attributeExists("error"));
  sprints.start(manager.getId(),p.getId(),sprint.getId());
  sprints.complete(manager.getId(),p.getId(),sprint.getId(),java.util.Map.of(story.getId(),new SprintService.Resolution("KEEP",null)));
  assertThatThrownBy(()->projects.planTask(manager.getId(),task.getId(),Priority.HIGH,null)).isInstanceOf(BusinessException.class);
  assertThat(task.getStory().getId()).isEqualTo(story.getId());
  var independent=projects.createTask(manager.getId(),p.getId(),"independent","",null,dev.getId());
  assertThatThrownBy(()->projects.planTask(manager.getId(),independent.getId(),Priority.HIGH,story.getId())).isInstanceOf(BusinessException.class);
 }
}
