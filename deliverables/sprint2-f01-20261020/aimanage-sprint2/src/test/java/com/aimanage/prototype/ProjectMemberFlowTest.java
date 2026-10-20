package com.aimanage.prototype;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ProjectMemberFlowTest {
    @Autowired ProjectTaskService service;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired TaskRepository tasks;
    @Autowired MockMvc mvc;
    User manager, developer, replacement, otherManager;
    Project project;
    @BeforeEach void setup() {
        String suffix = java.util.UUID.randomUUID().toString();
        manager=users.save(new User("m"+suffix,"123456","负责人",Role.PROJECT_MANAGER));
        developer=users.save(new User("d"+suffix,"123456","开发甲",Role.DEVELOPER));
        replacement=users.save(new User("r"+suffix,"123456","开发乙",Role.DEVELOPER));
        otherManager=users.save(new User("o"+suffix,"123456","其他负责人",Role.PROJECT_MANAGER));
        project=service.createProject(manager.getId(),"原项目","原简介");
        service.addMember(manager.getId(),project.getId(),developer.getId());
    }
    MockHttpSession session(User user) {
        MockHttpSession s=new MockHttpSession();s.setAttribute("userId",user.getId());s.setAttribute("role",user.getRole());s.setAttribute("displayName",user.getDisplayName());return s;
    }
    @Test void projectEditPersistsAndRejectsInvalidOrForeignWrites() {
        service.editProject(manager.getId(),project.getId()," 新项目 ","新简介");
        assertThat(projects.findById(project.getId()).orElseThrow().getName()).isEqualTo("新项目");
        assertThatThrownBy(()->service.editProject(otherManager.getId(),project.getId(),"非法","" )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.editProject(manager.getId(),project.getId()," ","" )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.editProject(manager.getId(),project.getId(),"正常","a".repeat(501))).isInstanceOf(BusinessException.class);
    }
    @Test void memberDetailsPersistAndPauseBlocksAssignmentAndStatusChanges() throws Exception {
        Task task=service.createTask(manager.getId(),project.getId(),"任务","",null,developer.getId());
        assertThatThrownBy(()->service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.PAUSED,"测试负责人","负责测试"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未完成");
        service.updateStatus(developer.getId(),task.getId(),TaskStatus.DONE,"测试通过");
        service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.PAUSED,"测试负责人","负责测试");
        var member=members.findByProjectIdAndUserId(project.getId(),developer.getId()).orElseThrow();
        assertThat(member.getProjectIdentity()).isEqualTo("测试负责人");
        assertThat(member.getResponsibilities()).isEqualTo("负责测试");
        assertThatThrownBy(()->service.createTask(manager.getId(),project.getId(),"任务2","",null,developer.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("暂停");
        assertThatThrownBy(()->service.updateStatus(developer.getId(),task.getId(),TaskStatus.DONE)).isInstanceOf(BusinessException.class);
        mvc.perform(get("/manager").session(session(manager))).andExpect(status().isOk());
        mvc.perform(get("/my-tasks").session(session(developer))).andExpect(content().string(org.hamcrest.Matchers.containsString("暂停参与，只读")));
        service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.ACTIVE,"测试负责人","负责测试");
        service.updateStatus(developer.getId(),task.getId(),TaskStatus.IN_PROGRESS,"补充检查");
        service.updateStatus(developer.getId(),task.getId(),TaskStatus.DONE,"补充检查通过");
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }
    @Test void requiresReassignmentBeforeRemovalAndKeepsAccountAndTask() {
        Task task=service.createTask(manager.getId(),project.getId(),"任务","",null,developer.getId());
        assertThatThrownBy(()->service.removeMember(manager.getId(),project.getId(),developer.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("转交");
        service.addMember(manager.getId(),project.getId(),replacement.getId());
        service.editTask(manager.getId(),task.getId(),"任务","",null,replacement.getId());
        service.removeMember(manager.getId(),project.getId(),developer.getId());
        assertThat(members.existsByProjectIdAndUserId(project.getId(),developer.getId())).isFalse();
        assertThat(users.existsById(developer.getId())).isTrue();
        assertThat(tasks.existsById(task.getId())).isTrue();
        assertThat(service.projectsForDeveloper(developer.getId())).isEmpty();
        service.addMember(manager.getId(),project.getId(),developer.getId());
        assertThat(members.findByProjectIdAndUserId(project.getId(),developer.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
    @Test void developerSeesOwnProjectAndTeamResponsibilitiesOnly() throws Exception {
        service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.ACTIVE,"前端开发","负责登录页面");
        service.createProject(otherManager.getId(),"不应可见项目","秘密");
        mvc.perform(get("/projects").session(session(developer))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("负责登录页面")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("不应可见项目"))));
        mvc.perform(get("/projects")).andExpect(redirectedUrl("/login"));
    }
    @Test void webEditAndRemovalRequireOwnerRoleAndToken() throws Exception {
        var s=session(manager);
        mvc.perform(get("/manager").session(s)).andExpect(status().isOk());
        String token=(String)s.getAttribute("projectCsrf");
        mvc.perform(post("/manager/projects/"+project.getId()+"/edit").session(s).param("name","网页修改").param("description","新简介").param("csrfToken",token)).andExpect(redirectedUrl("/manager"));
        assertThat(projects.findById(project.getId()).orElseThrow().getName()).isEqualTo("网页修改");
        mvc.perform(post("/manager/projects/"+project.getId()+"/members/"+developer.getId()+"/edit").session(s)
                .param("status","ACTIVE").param("projectIdentity","后端开发").param("responsibilities","实现接口").param("csrfToken",token)).andExpect(redirectedUrl("/manager"));
        assertThat(members.findByProjectIdAndUserId(project.getId(),developer.getId()).orElseThrow().getResponsibilities()).isEqualTo("实现接口");
        mvc.perform(post("/manager/projects/"+project.getId()+"/members/"+developer.getId()+"/remove").session(s)).andExpect(flash().attributeExists("error"));
        assertThat(members.existsByProjectIdAndUserId(project.getId(),developer.getId())).isTrue();
        mvc.perform(post("/manager/projects/"+project.getId()+"/members/"+developer.getId()+"/remove").session(session(developer)).param("csrfToken",token)).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/manager/projects/"+project.getId()+"/members/"+developer.getId()+"/remove").session(s).param("csrfToken",token)).andExpect(flash().attributeExists("success"));
        assertThat(members.existsByProjectIdAndUserId(project.getId(),developer.getId())).isFalse();
    }
    @Test void foreignManagerAndInvalidDetailsCannotChangeMembers() {
        assertThatThrownBy(()->service.updateMember(otherManager.getId(),project.getId(),developer.getId(),MemberStatus.PAUSED,"开发","" )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.removeMember(otherManager.getId(),project.getId(),developer.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.ACTIVE," ","" )).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.updateMember(manager.getId(),project.getId(),developer.getId(),MemberStatus.ACTIVE,"开发","a".repeat(501))).isInstanceOf(BusinessException.class);
        assertThat(members.findByProjectIdAndUserId(project.getId(),developer.getId()).orElseThrow().getStatus()).isEqualTo(MemberStatus.ACTIVE);
    }
}
