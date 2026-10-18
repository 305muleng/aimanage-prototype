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
 @Test void requirementDetailsGroupTheirStoriesAndSaveReturnsToTheSelectedItem() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"detail test","");projects.addMember(m.getId(),p.getId(),d.getId());
  var req=backlog.save(m.getId(),p.getId(),null,"REQUIREMENT","account requirements","description","",Priority.HIGH,null);
  var one=backlog.save(m.getId(),p.getId(),null,"STORY","login","description","accepted",Priority.HIGH,req.getId());
  backlog.save(m.getId(),p.getId(),null,"STORY","logout","","accepted",Priority.LOW,req.getId());
  var s=new MockHttpSession();s.setAttribute("userId",m.getId());s.setAttribute("role",m.getRole());
  var result=mvc.perform(get("/backlog").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk()).andReturn();
  @SuppressWarnings("unchecked") var grouped=(java.util.Map<Long,java.util.List<WorkItem>>)result.getModelAndView().getModel().get("storiesByRequirement");
  assertThat(grouped.get(req.getId())).extracting(WorkItem::getTitle).containsExactlyInAnyOrder("login","logout");
  mvc.perform(post("/backlog").session(s).param("projectId",p.getId().toString()).param("id",one.getId().toString()).param("type","STORY").param("title","updated login").param("acceptance","new criteria").param("priority","URGENT").param("requirementId",req.getId().toString()).param("csrfToken",s.getAttribute("backlogCsrf").toString()))
   .andExpect(redirectedUrl("/backlog?projectId="+p.getId()+"#item-"+one.getId()));
  assertThat(items.findById(one.getId()).orElseThrow().getAcceptance()).isEqualTo("new criteria");
  var memberSession=new MockHttpSession();memberSession.setAttribute("userId",d.getId());memberSession.setAttribute("role",d.getRole());
  var html=mvc.perform(get("/backlog").session(memberSession).param("projectId",p.getId().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(html).contains("updated login","new criteria").doesNotContain("class=\"bp-editor\"","保存修改");
 }
 @Test void defaultsToFirstProjectAndSwitchesWithoutAnOpenButton() throws Exception {
  var manager=users.findByUsername("manager").orElseThrow();
  var s=new MockHttpSession();s.setAttribute("userId",manager.getId());s.setAttribute("role",Role.PROJECT_MANAGER);
  var available=projects.projectsForManager(manager.getId());
  mvc.perform(get("/backlog").session(s)).andExpect(status().isOk())
   .andExpect(model().attribute("projectId",available.get(0).getId()))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("this.form.requestSubmit()")))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("打开项目需求"))))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("story-map-board")));
  var second=projects.createProject(manager.getId(),"switch project","");
  mvc.perform(get("/backlog").session(s).param("projectId",second.getId().toString()))
   .andExpect(status().isOk()).andExpect(model().attribute("projectId",second.getId()));
 }
 @Test void storyMapPlacementPersistsAndEnforcesPermissions() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();
  var p=projects.createProject(m.getId(),"map test","");projects.addMember(m.getId(),p.getId(),d.getId());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","地图故事","","验收",Priority.HIGH,null);
  assertThat(story.getMapLane()).isEqualTo("UNPLANNED");
  backlog.place(m.getId(),p.getId(),story.getId(),"EPIC2","SPRINT2","E2-10 多视图联动");
  assertThat(items.findById(story.getId()).orElseThrow().getMapFeature()).isEqualTo("E2-10 多视图联动");
  assertThatThrownBy(()->backlog.place(d.getId(),p.getId(),story.getId(),"EPIC1","SPRINT1","")).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->backlog.place(m.getId(),p.getId(),story.getId(),"INVALID","SPRINT1","")).isInstanceOf(BusinessException.class);
  var other=projects.createProject(m.getId(),"other map","");
  assertThatThrownBy(()->backlog.place(m.getId(),other.getId(),story.getId(),"EPIC1","SPRINT1","")).isInstanceOf(BusinessException.class);
  var s=new MockHttpSession();s.setAttribute("userId",m.getId());s.setAttribute("role",Role.PROJECT_MANAGER);
  mvc.perform(get("/backlog").param("projectId",p.getId().toString()).session(s)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("story-map-card"))).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("E2-10 多视图联动"))));
  mvc.perform(post("/backlog-map").session(s).param("projectId",p.getId().toString()).param("id",story.getId().toString()).param("epic","EPIC1").param("lane","SPRINT1")).andExpect(flash().attributeExists("error"));
  mvc.perform(post("/backlog-map").session(s).param("projectId",p.getId().toString()).param("id",story.getId().toString()).param("epic","EPIC3").param("lane","SPRINT3").param("csrfToken",s.getAttribute("backlogCsrf").toString())).andExpect(flash().attributeExists("success"));
  assertThat(story.getMapEpic()).isEqualTo("EPIC3");
 }
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
