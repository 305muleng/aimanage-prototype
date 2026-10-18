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
