package com.aimanage.prototype;

import com.aimanage.prototype.config.AccessPolicy;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional
class AccessControlTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ManagedRoleRepository roles;
    @Autowired ProjectTaskService service;
    @Autowired TaskRepository tasks;

    MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/login").param("username",username).param("password","123456"))
                .andExpect(status().is3xxRedirection()).andReturn().getRequest().getSession(false);
    }

    @Test void threeRolesHaveExplicitReadAndWriteRouteIsolation() throws Exception {
        String[] accounts={"admin","manager","developer"};
        String[] homes={"/admin/roles","/manager","/my-tasks"};
        for(int i=0;i<accounts.length;i++) {
            var session=login(accounts[i]);
            mvc.perform(get(homes[i]).session(session)).andExpect(status().isOk());
            mvc.perform(get("/access").session(session)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("三角色权限体系")));
            mvc.perform(get("/account").session(session)).andExpect(status().isOk());
            for(int j=0;j<homes.length;j++) if(i!=j) {
                mvc.perform(get(homes[j]).session(session)).andExpect(redirectedUrl("/login"));
                // No parameters: permission gate must run before binding or changing data.
                String write=j==0?"/admin/roles":j==1?"/manager/projects":"/my-tasks/1/status";
                mvc.perform(post(write).session(session)).andExpect(redirectedUrl("/login"));
            }
        }
    }

    @Test void anonymousAndMalformedOrDeletedIdentityCannotReachBusinessRoutes() throws Exception {
        for(String path:new String[]{"/account","/access","/projects","/manager","/admin/roles","/my-tasks","/preview/tasks"}) {
            mvc.perform(get(path)).andExpect(redirectedUrl("/login"));
        }
        var fake=new MockHttpSession();fake.setAttribute("role",Role.SYSTEM_ADMIN);
        fake.setAttribute("userId","4");
        mvc.perform(post("/admin/roles").session(fake)).andExpect(redirectedUrl("/login"));
        var deleted=new MockHttpSession();deleted.setAttribute("userId",Long.MAX_VALUE);deleted.setAttribute("role",Role.SYSTEM_ADMIN);
        mvc.perform(get("/admin/roles").session(deleted)).andExpect(redirectedUrl("/login"));
        assertThat(deleted.isInvalid()).isTrue();
    }

    @Test void servicesEnforceRoleAndProjectReadScope() {
        User manager=users.findByUsername("manager").orElseThrow();
        User developer=users.findByUsername("developer").orElseThrow();
        User outsider=users.findByUsername("developer2").orElseThrow();
        User admin=users.findByUsername("admin").orElseThrow();
        var project=service.createProject(manager.getId(),"权限范围验证","");
        service.addMember(manager.getId(),project.getId(),developer.getId());
        assertThat(service.members(developer.getId(),project.getId())).hasSize(1);
        assertThatThrownBy(()->service.members(outsider.getId(),project.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.members(admin.getId(),project.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.projectTasks(developer.getId(),project.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.projectsForManager(admin.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.myTasks(manager.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.developers(developer.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.taskCount(admin.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.doneTaskCount(developer.getId())).isInstanceOf(BusinessException.class);
        var other=users.save(new User("other_manager_access","123456","其他负责人",Role.PROJECT_MANAGER));
        assertThatThrownBy(()->service.members(other.getId(),project.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.projectTasks(other.getId(),project.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.editProject(other.getId(),project.getId(),"越权","")).isInstanceOf(BusinessException.class);
    }

    @Test void staleAssigneeRoleCannotUpdateTaskEvenThroughService() {
        var manager=users.findByUsername("manager").orElseThrow();
        var developer=users.findByUsername("developer").orElseThrow();
        var project=service.createProject(manager.getId(),"任务权限验证","");
        service.addMember(manager.getId(),project.getId(),developer.getId());
        var task=service.createTask(manager.getId(),project.getId(),"校验任务","",null,developer.getId());
        assertThatThrownBy(()->service.updateStatus(manager.getId(),task.getId(),TaskStatus.DONE)).isInstanceOf(BusinessException.class);
        developer.assignRole(roles.findByBuiltInCode("SYSTEM_ADMIN").orElseThrow());
        assertThatThrownBy(()->service.updateStatus(developer.getId(),task.getId(),TaskStatus.DONE)).isInstanceOf(BusinessException.class);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test void policyDeniesUnknownRoutesAndUsesPathBoundaries() {
        for(Role role:Role.values()) {
            assertThat(AccessPolicy.allows(role,"/new-business-route")).isFalse();
            assertThat(AccessPolicy.allows(role,"/manager-escape")).isFalse();
        }
        assertThat(AccessPolicy.allows(null,"/account")).isFalse();
        assertThat(AccessPolicy.allows(Role.SYSTEM_ADMIN,"/admin/future-action")).isTrue();
    }
}
