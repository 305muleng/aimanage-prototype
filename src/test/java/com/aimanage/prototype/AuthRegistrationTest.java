package com.aimanage.prototype;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.repository.UserRepository;
import com.aimanage.prototype.service.AuthService;
import com.aimanage.prototype.service.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRegistrationTest {
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    @Test void registrationPersistsHashAndCannotChoosePrivilegedRole() throws Exception {
        mvc.perform(post("/register").param("username", "new_web_user").param("displayName", "新开发人员")
                .param("password", "secret123").param("confirmPassword", "secret123").param("role", "PROJECT_MANAGER"))
                .andExpect(redirectedUrl("/login"));
        var user = users.findByUsername("new_web_user").orElseThrow();
        assertThat(user.getRole()).isEqualTo(Role.DEVELOPER);
        assertThat(user.getPassword()).startsWith("pbkdf2$").isNotEqualTo("secret123");
        assertThat(auth.authenticate("new_web_user", "secret123").getId()).isEqualTo(user.getId());
        assertThatThrownBy(() -> auth.authenticate("new_web_user", "wrong")).isInstanceOf(BusinessException.class);
    }

    @Test void rejectsInvalidInputAndCaseInsensitiveDuplicate() {
        auth.register("unique_user", "测试", "secret123", "secret123");
        assertThatThrownBy(() -> auth.register("UNIQUE_USER", "测试", "secret123", "secret123"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已被注册");
        assertThatThrownBy(() -> auth.register("bad space", "测试", "secret123", "secret123"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> auth.register("valid_user", " ", "secret123", "secret123"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> auth.register("valid_user", "测试", "123", "123"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> auth.register("valid_user", "测试", "secret123", "different"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不一致");
    }

    @Test void loginRotatesSessionAndLogoutBlocksAccess() throws Exception {
        auth.register("session_user", "测试", "secret123", "secret123");
        MockHttpSession session = new MockHttpSession();
        String oldId = session.getId();
        mvc.perform(post("/login").session(session).param("username", "session_user").param("password", "secret123"))
                .andExpect(redirectedUrl("/my-tasks"));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/my-tasks").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session)).andExpect(redirectedUrl("/login"));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/my-tasks")).andExpect(redirectedUrl("/login"));
    }

    @Test void failedRegistrationDoesNotEchoPasswords() throws Exception {
        mvc.perform(post("/register").param("username", "form_user").param("displayName", "测试")
                .param("password", "sensitive123").param("confirmPassword", "different"))
                .andExpect(status().isOk()).andExpect(view().name("register"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("两次输入的密码不一致")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive123"))));
        mvc.perform(get("/register")).andExpect(status().isOk());
    }

    @Test void administratorCanAuthenticateButCannotOperateManagerProjects() throws Exception {
        auth.ensureDemoAdministrator();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/login").session(session).param("username", "admin").param("password", "123456"))
                .andExpect(redirectedUrl("/admin/roles"));
        assertThat(session.getAttribute("role")).isEqualTo(Role.SYSTEM_ADMIN);
        mvc.perform(get("/manager").session(session)).andExpect(redirectedUrl("/login"));
    }
}
