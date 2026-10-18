package com.aimanage.prototype;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RoleManagementFlowTest {
    @Autowired RoleManagementService service;
    @Autowired AuthService auth;
    @Autowired ManagedRoleRepository roles;
    @Autowired UserRepository users;
    @Autowired ProjectTaskService projects;
    @Autowired MockMvc mvc;
    User admin,target;
    String suffix;
    @BeforeEach void setup() {
        service.initializeBuiltIns();auth.ensureDemoAdministrator();service.linkExistingUsers();
        admin=users.findByUsername("admin").orElseThrow();
        suffix=java.util.UUID.randomUUID().toString().replace("-", "");
        target=auth.register("r"+suffix,"测试用户","secret123","secret123");
    }
    MockHttpSession session(User user) {
        var s=new MockHttpSession();s.setAttribute("userId",user.getId());s.setAttribute("role",user.getRole());s.setAttribute("displayName",user.getDisplayName());return s;
    }
    @Test void createsPersistedCustomRoleAndAssignsUser() {
        var custom=service.create(admin.getId(),"测试工程师"+suffix,"负责质量验收",Role.DEVELOPER);
        service.assign(admin.getId(),target.getId(),custom.getId());
        User saved=users.findById(target.getId()).orElseThrow();
        assertThat(saved.getManagedRoleName()).isEqualTo(custom.getName());
        assertThat(saved.getRole()).isEqualTo(Role.DEVELOPER);
        assertThat(auth.authenticate(target.getUsername(),"secret123").getManagedRole().getId()).isEqualTo(custom.getId());
    }
    @Test void validatesNamesDescriptionsAndAdminAuthority() {
        var custom=service.create(admin.getId(),"role_"+suffix,"",Role.DEVELOPER);
        assertThatThrownBy(()->service.create(admin.getId(),custom.getName().toUpperCase(),"",Role.DEVELOPER)).isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        assertThatThrownBy(()->service.create(admin.getId()," ","",Role.DEVELOPER)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create(admin.getId(),"valid", "a".repeat(501),Role.DEVELOPER)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.create(target.getId(),"illegal","",Role.SYSTEM_ADMIN)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.assign(target.getId(),target.getId(),custom.getId())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.assign(admin.getId(),target.getId(),Long.MAX_VALUE)).isInstanceOf(BusinessException.class);
    }
    @Test void cannotDemoteLastAdmin() {
        var developer=roles.findByBuiltInCode("DEVELOPER").orElseThrow();
        assertThatThrownBy(()->service.assign(admin.getId(),admin.getId(),developer.getId())).isInstanceOf(BusinessException.class).hasMessageContaining("最后一个");
        assertThat(users.findById(admin.getId()).orElseThrow().getRole()).isEqualTo(Role.SYSTEM_ADMIN);
    }
    @Test void assignmentRefreshesExistingSessionAndRevokesOldAuthority() throws Exception {
        MockHttpSession targetSession=session(target);
        var managerRole=roles.findByBuiltInCode("PROJECT_MANAGER").orElseThrow();
        service.assign(admin.getId(),target.getId(),managerRole.getId());
        mvc.perform(get("/manager").session(targetSession)).andExpect(status().isOk());
        assertThat(targetSession.getAttribute("role")).isEqualTo(Role.PROJECT_MANAGER);
        service.assign(admin.getId(),target.getId(),roles.findByBuiltInCode("DEVELOPER").orElseThrow().getId());
        mvc.perform(get("/manager").session(targetSession)).andExpect(redirectedUrl("/login"));
        assertThat(targetSession.getAttribute("role")).isEqualTo(Role.DEVELOPER);
        mvc.perform(get("/admin/roles").session(targetSession)).andExpect(redirectedUrl("/login"));
        // A stale or forged admin session must be corrected from the database.
        targetSession.setAttribute("role",Role.SYSTEM_ADMIN);
        mvc.perform(post("/admin/roles").session(targetSession).param("name","illegal").param("accessType","SYSTEM_ADMIN"))
                .andExpect(redirectedUrl("/login"));
    }
    @Test void webCreationAssignmentAndCsrfChecks() throws Exception {
        var s=session(admin);
        mvc.perform(get("/admin/roles").session(s)).andExpect(status().isOk()).andExpect(view().name("roles"));
        String token=(String)s.getAttribute("roleCsrf");
        String name="网页角色"+suffix;
        mvc.perform(post("/admin/roles").session(s).param("name",name).param("description","角色说明").param("accessType","DEVELOPER").param("csrfToken",token))
                .andExpect(redirectedUrl("/admin/roles"));
        var role=roles.findAll().stream().filter(r->r.getName().equals(name)).findFirst().orElseThrow();
        mvc.perform(post("/admin/roles/users/"+target.getId()).session(s).param("roleId",role.getId().toString()))
                .andExpect(flash().attributeExists("error"));
        assertThat(users.findById(target.getId()).orElseThrow().getManagedRole().getId()).isNotEqualTo(role.getId());
        mvc.perform(post("/admin/roles/users/"+target.getId()).session(s).param("roleId",role.getId().toString()).param("csrfToken",token))
                .andExpect(flash().attributeExists("success"));
        mvc.perform(get("/admin/roles").session(s)).andExpect(content().string(org.hamcrest.Matchers.containsString(name)))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("pbkdf2$"))));
        mvc.perform(get("/account").session(session(users.findById(target.getId()).orElseThrow())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(name)));
        mvc.perform(get("/admin/roles")).andExpect(redirectedUrl("/login"));
    }
    @Test void refusesTypeChangesThatWouldStrandProjectResponsibilities() {
        var managerRole=roles.findByBuiltInCode("PROJECT_MANAGER").orElseThrow();
        var developerRole=roles.findByBuiltInCode("DEVELOPER").orElseThrow();
        service.assign(admin.getId(),target.getId(),managerRole.getId());
        var project=projects.createProject(target.getId(),"角色验证项目","");
        assertThatThrownBy(()->service.assign(admin.getId(),target.getId(),developerRole.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("负责项目");
        var member=auth.register("member"+suffix,"成员","secret123","secret123");
        projects.addMember(target.getId(),project.getId(),member.getId());
        assertThatThrownBy(()->service.assign(admin.getId(),member.getId(),managerRole.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("项目成员");
        var custom=service.create(admin.getId(),"工程师"+suffix,"",Role.DEVELOPER);
        service.assign(admin.getId(),member.getId(),custom.getId());
        assertThat(users.findById(member.getId()).orElseThrow().getManagedRoleName()).isEqualTo(custom.getName());
    }
}
