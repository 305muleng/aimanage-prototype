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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class GanttFlowTest {
 @Autowired MockMvc mvc;@Autowired ProjectTaskService service;@Autowired UserRepository users;@Autowired BacklogService backlog;
 MockHttpSession session(User u){var s=new MockHttpSession();s.setAttribute("userId",u.getId());s.setAttribute("role",u.getRole());return s;}
 @Test void rendersRealBarsAndSavesValidatedSchedule() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=service.createProject(m.getId(),"gantt","");service.addMember(m.getId(),p.getId(),d.getId());
  var task=service.createTask(m.getId(),p.getId(),"甘特测试任务","",null,d.getId());var start=LocalDate.of(2026,10,11);
  service.scheduleTask(m.getId(),task.getId(),start,start.plusDays(3));
  assertThatThrownBy(()->service.scheduleTask(m.getId(),task.getId(),start,start.minusDays(1))).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->service.scheduleTask(d.getId(),task.getId(),start,start)).isInstanceOf(BusinessException.class);
  var s=session(m);
  mvc.perform(get("/sprints").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("gantt-bar"))).andExpect(content().string(org.hamcrest.Matchers.containsString("甘特测试任务")));
  mvc.perform(post("/sprints").session(s).param("projectId",p.getId().toString()).param("taskId",task.getId().toString()).param("startDate","2026-10-12").param("dueDate","2026-10-15")).andExpect(flash().attributeExists("error"));
  mvc.perform(post("/sprints").session(s).param("projectId",p.getId().toString()).param("taskId",task.getId().toString()).param("startDate","2026-10-12").param("dueDate","2026-10-15").param("csrfToken",s.getAttribute("ganttCsrf").toString())).andExpect(flash().attributeExists("success"));
  assertThat(task.getStartDate()).isEqualTo(LocalDate.of(2026,10,12));
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","分组故事","","验收",Priority.MEDIUM,null);backlog.place(m.getId(),p.getId(),story.getId(),"EPIC2","SPRINT2","");
  service.planTask(m.getId(),task.getId(),Priority.HIGH,story.getId());
  mvc.perform(get("/sprints").session(s).param("projectId",p.getId().toString()).param("lane","SPRINT2")).andExpect(model().attribute("total",1));
  mvc.perform(get("/sprints").session(s).param("projectId",p.getId().toString()).param("lane","SPRINT1")).andExpect(model().attribute("total",0));
 }
 @Test void roleAndProjectIsolation() throws Exception {
  mvc.perform(get("/sprints")).andExpect(redirectedUrl("/login"));
  mvc.perform(get("/sprints").session(session(users.findByUsername("admin").orElseThrow()))).andExpect(redirectedUrl("/login"));
  var m=users.findByUsername("manager").orElseThrow();var out=users.findByUsername("developer2").orElseThrow();var p=service.createProject(m.getId(),"private","");
  mvc.perform(get("/sprints").session(session(out)).param("projectId",p.getId().toString())).andExpect(redirectedUrl("/sprints"));
 }
}
