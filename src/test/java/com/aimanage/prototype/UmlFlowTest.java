package com.aimanage.prototype;
import com.aimanage.prototype.domain.*;import com.aimanage.prototype.repository.*;import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.boot.test.context.SpringBootTest;import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.test.context.ActiveProfiles;import org.springframework.transaction.annotation.Transactional;import org.springframework.mock.web.MockHttpSession;import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional class UmlFlowTest {
 @Autowired UmlService uml;@Autowired UserRepository users;@Autowired ProjectTaskService projects;@Autowired BacklogService backlog;@Autowired MockMvc mvc;
 MockHttpSession session(User u){var s=new MockHttpSession();s.setAttribute("userId",u.getId());s.setAttribute("role",u.getRole());return s;}
 @Test void wholeDiagramLinksAreDeduplicatedScopedAndRespectTaskVisibility() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var other=users.findByUsername("developer2").orElseThrow();
  var p=projects.createProject(m.getId(),"diagram links","");projects.addMember(m.getId(),p.getId(),d.getId());projects.addMember(m.getId(),p.getId(),other.getId());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","linked story","","accepted",Priority.HIGH,null);
  var mine=projects.createTask(m.getId(),p.getId(),"linked task","",null,d.getId());var theirs=projects.createTask(m.getId(),p.getId(),"restricted task title","",null,other.getId());
  var v=uml.quick(m.getId(),p.getId(),UmlKind.CLASS);var diagram=uml.data(m.getId(),v.getId());
  for(var n:diagram.nodes())uml.node(m.getId(),v.getId(),n.id(),"CLASS",n.label(),"",story.getId(),mine.getId());
  uml.node(m.getId(),v.getId(),null,"CLASS","other class","",null,theirs.getId());
  mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString()))
   .andExpect(status().isOk()).andExpect(model().attribute("linkedStories",org.hamcrest.Matchers.hasSize(1))).andExpect(model().attribute("linkedTasks",org.hamcrest.Matchers.hasSize(2)))
   .andExpect(content().string(org.hamcrest.Matchers.containsString("#edit-item-"+story.getId()))).andExpect(content().string(org.hamcrest.Matchers.containsString("editTask="+mine.getId())));
  mvc.perform(get("/uml").session(session(d)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString()))
   .andExpect(status().isOk()).andExpect(model().attribute("linkedTasks",org.hamcrest.Matchers.hasSize(1))).andExpect(model().attribute("restrictedLinkedTasks",true))
   .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("restricted task title"))));
  var empty=uml.create(m.getId(),p.getId(),UmlKind.SEQUENCE,"no links");
  mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",empty.getId().toString()))
   .andExpect(model().attribute("linkedStories",org.hamcrest.Matchers.empty())).andExpect(model().attribute("linkedTasks",org.hamcrest.Matchers.empty()));
  var first=diagram.nodes().get(0);uml.node(m.getId(),v.getId(),first.id(),"CLASS",first.label(),"",null,null);
  mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(model().attribute("linkedStories",org.hamcrest.Matchers.hasSize(1)));
  var second=diagram.nodes().get(1);uml.node(m.getId(),v.getId(),second.id(),"CLASS",second.label(),"",null,null);
  mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(model().attribute("linkedStories",org.hamcrest.Matchers.empty())).andExpect(model().attribute("linkedTasks",org.hamcrest.Matchers.hasSize(1)));
 }
 @Autowired UmlViewRepository viewRepo;@Autowired UmlNodeRepository nodeRepo;@Autowired UmlEdgeRepository edgeRepo;@Autowired WorkItemRepository storyRepo;@Autowired TaskRepository taskRepo;
 @Test void deletingDiagramsCleansChildrenPreservesLinksAndHandlesLastDiagram() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=projects.createProject(m.getId(),"delete diagrams","");projects.addMember(m.getId(),p.getId(),d.getId());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","kept story","","accepted",Priority.HIGH,null);var task=projects.createTask(m.getId(),p.getId(),"kept task","",null,d.getId());
  var removed=uml.quick(m.getId(),p.getId(),UmlKind.CLASS);var original=uml.data(m.getId(),removed.getId());var node=original.nodes().get(0);
  uml.node(m.getId(),removed.getId(),node.id(),"CLASS","linked class","",story.getId(),task.getId());
  var kept=uml.quick(m.getId(),p.getId(),UmlKind.SEQUENCE);var keptData=uml.data(m.getId(),kept.getId());var s=session(m);
  mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("uml-delete-view")));
  mvc.perform(post("/uml").session(s).param("action","deleteView").param("projectId",p.getId().toString()).param("viewId",removed.getId().toString()).param("csrfToken",s.getAttribute("umlCsrf").toString())).andExpect(redirectedUrl("/uml?projectId="+p.getId())).andExpect(flash().attribute("success","UML 图已删除"));
  assertThat(viewRepo.existsById(removed.getId())).isFalse();
  for(var n:original.nodes())assertThat(nodeRepo.existsById(n.id())).isFalse();
  for(var e:original.edges())assertThat(edgeRepo.existsById(e.id())).isFalse();
  assertThat(storyRepo.existsById(story.getId())).isTrue();assertThat(taskRepo.existsById(task.getId())).isTrue();assertThat(uml.data(m.getId(),kept.getId())).isEqualTo(keptData);
  mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(model().attribute("viewId",kept.getId()));
  mvc.perform(post("/uml").session(s).param("action","deleteView").param("projectId",p.getId().toString()).param("viewId",kept.getId().toString()).param("csrfToken",s.getAttribute("umlCsrf").toString())).andExpect(flash().attributeExists("success"));
  mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("尚未创建视图")));
  assertThat(uml.list(m.getId(),p.getId())).isEmpty();
 }
 @Test void deletingDiagramsRequiresOwnerMatchingProjectAndCsrf() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=projects.createProject(m.getId(),"delete guards","");projects.addMember(m.getId(),p.getId(),d.getId());var other=projects.createProject(m.getId(),"other project","");var v=uml.quick(m.getId(),p.getId(),UmlKind.ACTIVITY);var original=uml.data(m.getId(),v.getId());
  assertThatThrownBy(()->uml.deleteView(d.getId(),p.getId(),v.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->uml.deleteView(m.getId(),other.getId(),v.getId())).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->uml.deleteView(m.getId(),p.getId(),null)).isInstanceOf(BusinessException.class);
  var s=session(m);mvc.perform(post("/uml").session(s).param("action","deleteView").param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(flash().attributeExists("error"));
  mvc.perform(get("/uml").session(session(d)).param("projectId",p.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uml-delete-view"))));
  assertThat(uml.data(m.getId(),v.getId())).isEqualTo(original);
 }
 @Test void classNameAndMembersAreVisibleAndUpdateTheSameNode() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=projects.createProject(m.getId(),"class edit","");projects.addMember(m.getId(),p.getId(),d.getId());
  var story=backlog.save(m.getId(),p.getId(),null,"STORY","login","","verified",Priority.HIGH,null);var task=projects.createTask(m.getId(),p.getId(),"login task","",null,d.getId());
  var v=uml.quick(m.getId(),p.getId(),UmlKind.CLASS);var before=uml.data(m.getId(),v.getId());var n=before.nodes().get(0);var s=session(m);
  var html=mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
  assertThat(html).contains("类名","属性 / 方法","form=\"uml-node-form\"","保存","新增");assertThat(html.indexOf("name=\"details\"")).isLessThan(html.indexOf("<details class=\"uml-advanced\""));
  assertThat(java.util.regex.Pattern.compile("name=\"details\"").matcher(html).results().count()).isEqualTo(1);
  String members="- id: Long\n- name: String\n- email: String\n- active: boolean\n+ login(): boolean\n+ logout(): void";
  mvc.perform(post("/uml").session(s).param("action","node").param("projectId",p.getId().toString()).param("viewId",v.getId().toString()).param("id",n.id().toString()).param("type","CLASS").param("label","User").param("details",members).param("storyId",story.getId().toString()).param("taskId",task.getId().toString()).param("csrfToken",s.getAttribute("umlCsrf").toString())).andExpect(flash().attributeExists("success"));
  var after=uml.data(m.getId(),v.getId());var updated=after.nodes().get(0);assertThat(after.nodes()).hasSize(2);assertThat(after.edges()).isEqualTo(before.edges());
  assertThat(updated.id()).isEqualTo(n.id());assertThat(updated.x()).isEqualTo(n.x());assertThat(updated.y()).isEqualTo(n.y());assertThat(updated.label()).isEqualTo("User");assertThat(updated.details()).isEqualTo(members);assertThat(updated.storyId()).isEqualTo(story.getId());assertThat(updated.taskId()).isEqualTo(task.getId());
  mvc.perform(get("/uml/data").session(session(m)).param("viewId",v.getId().toString())).andExpect(jsonPath("$.nodes[0].label").value("User")).andExpect(jsonPath("$.nodes[0].details").value(members));
 }
 @Test void edgeNamesCanBeEditedWithoutReplacingConnections() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"rename edge","");
  for(var kind:UmlKind.values()){
   var v=uml.quick(m.getId(),p.getId(),kind);var original=uml.data(m.getId(),v.getId()).edges().get(0);var s=session(m);
   mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(content().string(org.hamcrest.Matchers.containsString("uml-edge-form")));
   mvc.perform(post("/uml").session(s).param("action","renameEdge").param("projectId",p.getId().toString()).param("viewId",v.getId().toString()).param("id",original.id().toString()).param("label","  提交登录请求  ").param("csrfToken",s.getAttribute("umlCsrf").toString())).andExpect(flash().attributeExists("success"));
   var changed=uml.data(m.getId(),v.getId()).edges();assertThat(changed).hasSize(1);assertThat(changed.get(0)).isEqualTo(new UmlService.Edge(original.id(),original.source(),original.target(),"提交登录请求"));
   uml.renameEdge(m.getId(),v.getId(),original.id(),"");assertThat(uml.data(m.getId(),v.getId()).edges().get(0).label()).isEmpty();
  }
 }
 @Test void renamingEdgesRequiresOwnershipCorrectViewAndCsrf() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=projects.createProject(m.getId(),"rename guards","");projects.addMember(m.getId(),p.getId(),d.getId());
  var v=uml.quick(m.getId(),p.getId(),UmlKind.SEQUENCE);var e=uml.data(m.getId(),v.getId()).edges().get(0);var other=uml.quick(m.getId(),p.getId(),UmlKind.SEQUENCE);
  assertThatThrownBy(()->uml.renameEdge(d.getId(),v.getId(),e.id(),"blocked")).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->uml.renameEdge(m.getId(),other.getId(),e.id(),"wrong view")).isInstanceOf(BusinessException.class);
  assertThatThrownBy(()->uml.renameEdge(m.getId(),v.getId(),e.id(),"x".repeat(101))).isInstanceOf(BusinessException.class);
  var s=session(m);mvc.perform(post("/uml").session(s).param("action","renameEdge").param("projectId",p.getId().toString()).param("viewId",v.getId().toString()).param("id",e.id().toString()).param("label","no token")).andExpect(flash().attributeExists("error"));
  assertThat(uml.data(m.getId(),v.getId()).edges().get(0)).isEqualTo(e);
  mvc.perform(get("/uml").session(session(d)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"uml-edge-form\""))));
 }
 @Test void oneClickFramesKeepExistingDiagramsAndRenderSimpleTools() throws Exception {
  var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"quick test","");
  var existing=uml.create(m.getId(),p.getId(),UmlKind.CLASS,"original");
  for(var kind:UmlKind.values()){var v=uml.quick(m.getId(),p.getId(),kind);var data=uml.data(m.getId(),v.getId());assertThat(data.nodes()).hasSize(2);assertThat(data.edges()).hasSize(1);
   mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("uml-connect"))).andExpect(content().string(org.hamcrest.Matchers.containsString("新建图")));
  }
  assertThat(uml.list(m.getId(),p.getId())).hasSize(5);assertThat(uml.data(m.getId(),existing.getId()).nodes()).isEmpty();
 }
 @Test void validNodeTypeChangesDoNotLoseIdentityOrPosition(){
  var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"type change","");var v=uml.quick(m.getId(),p.getId(),UmlKind.USE_CASE);
  var n=uml.data(m.getId(),v.getId()).nodes().get(0);uml.node(m.getId(),v.getId(),n.id(),"USECASE","new usecase","",null,null);
  var changed=uml.data(m.getId(),v.getId()).nodes().get(0);assertThat(changed.id()).isEqualTo(n.id());assertThat(changed.x()).isEqualTo(n.x());assertThat(changed.type()).isEqualTo("USECASE");
  assertThatThrownBy(()->uml.node(m.getId(),v.getId(),n.id(),"CLASS","illegal","",null,null)).isInstanceOf(BusinessException.class);
 }
 @Test void fourDiagramTypesRenderAndSaveElementsEdgesAndPositions() throws Exception {
 var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var p=projects.createProject(m.getId(),"uml project","");projects.addMember(m.getId(),p.getId(),d.getId());
 for(var kind:UmlKind.values()){
 var v=uml.create(m.getId(),p.getId(),kind,kind.getLabel());String type=switch(kind){case CLASS->"CLASS";case USE_CASE->"USECASE";case SEQUENCE->"PARTICIPANT";case ACTIVITY->"ACTIVITY";};
 var n=uml.node(m.getId(),v.getId(),null,type,"节点","属性\n方法",null,null);var n2=uml.node(m.getId(),v.getId(),null,type,"终点","",null,null);uml.edge(m.getId(),v.getId(),n.getId(),n2.getId(),"调用");
 uml.move(m.getId(),v.getId(),n.getId(),500,300);
 var data=uml.data(d.getId(),v.getId());assertThat(data.nodes()).hasSize(2);assertThat(data.edges()).hasSize(1);assertThat(data.nodes().get(0).x()).isEqualTo(500);
 mvc.perform(get("/uml").session(session(m)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("uml-canvas")));
 mvc.perform(get("/uml/data").session(session(d)).param("viewId",v.getId().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].x").value(500));
 }
 }
 @Test void linksAreProjectScopedAndMembersCannotMutate() throws Exception {
 var m=users.findByUsername("manager").orElseThrow();var d=users.findByUsername("developer").orElseThrow();var out=users.findByUsername("developer2").orElseThrow();var p=projects.createProject(m.getId(),"links","");projects.addMember(m.getId(),p.getId(),d.getId());
 var story=backlog.save(m.getId(),p.getId(),null,"STORY","story","","criteria",Priority.HIGH,null);var task=projects.createTask(m.getId(),p.getId(),"task","",null,d.getId());var v=uml.create(m.getId(),p.getId(),UmlKind.CLASS,"classes");
 var n=uml.node(m.getId(),v.getId(),null,"CLASS","User","name: String",story.getId(),task.getId());assertThat(uml.data(m.getId(),v.getId()).nodes().get(0).storyId()).isEqualTo(story.getId());
 assertThatThrownBy(()->uml.move(d.getId(),v.getId(),n.getId(),0,0)).isInstanceOf(BusinessException.class);
 assertThatThrownBy(()->uml.data(out.getId(),v.getId())).isInstanceOf(BusinessException.class);
 var p2=projects.createProject(m.getId(),"other","");var story2=backlog.save(m.getId(),p2.getId(),null,"STORY","other","","criteria",Priority.LOW,null);
 assertThatThrownBy(()->uml.node(m.getId(),v.getId(),n.getId(),"CLASS","User","",story2.getId(),null)).isInstanceOf(BusinessException.class);
 var v2=uml.create(m.getId(),p2.getId(),UmlKind.CLASS,"other");var n2=uml.node(m.getId(),v2.getId(),null,"CLASS","other","",null,null);
 assertThatThrownBy(()->uml.edge(m.getId(),v.getId(),n.getId(),n2.getId(),"")).isInstanceOf(BusinessException.class);
 mvc.perform(get("/uml").session(session(d)).param("projectId",p.getId().toString()).param("viewId",v.getId().toString())).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uml-node-form"))));
 }
 @Test void writesRequireCsrfAndAnonymousRequestsAreDenied() throws Exception {
 var m=users.findByUsername("manager").orElseThrow();var p=projects.createProject(m.getId(),"csrf","");var s=session(m);
 mvc.perform(get("/uml").session(s).param("projectId",p.getId().toString())).andExpect(status().isOk());
 mvc.perform(post("/uml").session(s).param("action","view").param("projectId",p.getId().toString()).param("kind","CLASS").param("name","no token")).andExpect(flash().attributeExists("error"));
 assertThat(uml.list(m.getId(),p.getId())).isEmpty();
 mvc.perform(post("/uml").session(s).param("action","view").param("projectId",p.getId().toString()).param("kind","CLASS").param("name","with token").param("csrfToken",s.getAttribute("umlCsrf").toString())).andExpect(flash().attributeExists("success"));
 assertThat(uml.list(m.getId(),p.getId())).hasSize(1);
 mvc.perform(get("/uml")).andExpect(redirectedUrl("/login"));
 }
}
