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
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class BacklogFlowTest {
 @Autowired BacklogService backlog; @Autowired ProjectTaskService projects; @Autowired UserRepository users; @Autowired WorkItemRepository items; @Autowired MockMvc mvc;
 @Test void savesEditsAssociatesAndEnforcesProjectScope() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var out=users.findByUsername("developer2").orElseThrow();
  var p=projects.createProject(m.getId(),"backlog test","");projects.addMember(m.getId(),p.getId(),d.getId());
  var requirement=backlog.save(m.getId(),p.getId(),null,"REQUIREMENT","需求","说明","",Priority.HIGH,null);
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","用户故事","作为用户","完成时显示成功",Priority.MEDIUM,requirement.getId());
  backlog.save(m.getId(),p.getId(),story.getId(),"STORY","修改故事","修改说明","验收标准",Priority.URGENT,requirement.getId());
  assertThat(items.findById(story.getId()).orElseThrow().getPriority()).isEqualTo(Priority.URGENT);
  assertThat(backlog.list(d.getId(),p.getId())).hasSize(2);
  assertThatThrownBy(()->backlog.list(out.getId(),p.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->backlog.save(d.getId(),p.getId(),null,"STORY","越权","","验收",Priority.LOW,null)).isInstanceOf(BusinessException.class);
  var task=projects.createTask(m.getId(),p.getId(),"task","",null,d.getId());projects.planTask(m.getId(),task.getId(),Priority.HIGH,story.getId());
  assertThat(task.getStory().getId()).isEqualTo(story.getId());assertThat(task.getPriority()).isEqualTo(Priority.HIGH);
  var other=projects.createProject(m.getId(),"other","");var otherStory=backlog.save(m.getId(),other.getId(),null,"STORY","other","","criteria",Priority.LOW,null);
  assertThatThrownBy(()->projects.planTask(m.getId(),task.getId(),Priority.LOW,otherStory.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->projects.planTask(m.getId(),task.getId(),Priority.LOW,requirement.getId())).isInstanceOf(BusinessException.class);
  var s=new MockHttpSession();s.setAttribute("userId",m.getId());s.setAttribute("role",Role.PROJECT_MANAGER);
  mvc.perform(get("/backlog").param("projectId",p.getId().toString()).session(s)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("修改故事")));
  mvc.perform(get("/manager").session(s)).andExpect(status().isOk());
  mvc.perform(post("/backlog").session(s).param("projectId",p.getId().toString()).param("type","STORY").param("title","missing csrf").param("acceptance","ok").param("priority","LOW")).andExpect(flash().attributeExists("error"));
  assertThat(backlog.list(m.getId(),p.getId())).hasSize(2);
 }
 @Test void validatesAcceptanceAndPreservesLegacyTaskDefaults(){
  var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"validation","");
  assertThatThrownBy(()->backlog.save(m.getId(),p.getId(),null,"STORY","story","","",Priority.MEDIUM,null)).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->backlog.save(m.getId(),p.getId(),null,"REQUIREMENT","x".repeat(121),"","",Priority.LOW,null)).isInstanceOf(BusinessException.class);
  assertThat(new Task("old","",null,p,m).getPriority()).isEqualTo(Priority.MEDIUM);
 }
}
