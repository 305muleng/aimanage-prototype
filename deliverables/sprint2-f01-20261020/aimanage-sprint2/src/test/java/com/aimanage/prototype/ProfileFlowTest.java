package com.aimanage.prototype;

import com.aimanage.prototype.service.AuthService;
import com.aimanage.prototype.service.ProfileService;
import com.aimanage.prototype.service.BusinessException;
import com.aimanage.prototype.repository.UserRepository;
import com.aimanage.prototype.domain.Role;
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
class ProfileFlowTest {
    @Autowired AuthService auth;
    @Autowired ProfileService profiles;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    private MockHttpSession login(String username) throws Exception {
        auth.register(username, "原姓名", "secret123", "secret123");
        var result = mvc.perform(post("/login").param("username", username).param("password", "secret123"))
                .andExpect(redirectedUrl("/my-tasks")).andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/account").session(session)).andExpect(status().isOk()).andExpect(view().name("account"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("pbkdf2$"))));
        return session;
    }

    @Test void savesOnlyOwnProfileAndRefreshesSessionName() throws Exception {
        var session = login("profile_own");
        var other = auth.register("profile_other", "其他人", "secret123", "secret123");
        mvc.perform(post("/account").session(session).param("csrfToken", (String)session.getAttribute("profileCsrfToken"))
                .param("displayName", " 新姓名 ").param("email", "test@example.com").param("phone", "+86 13800000000")
                .param("bio", "团队开发").param("userId", other.getId().toString()).param("role", "SYSTEM_ADMIN")
                .param("username", "hacked").param("password", "hacked"))
                .andExpect(redirectedUrl("/account"));
        var own = users.findByUsername("profile_own").orElseThrow();
        assertThat(own.getDisplayName()).isEqualTo("新姓名");
        assertThat(own.getEmail()).isEqualTo("test@example.com");
        assertThat(own.getPhone()).isEqualTo("+86 13800000000");
        assertThat(own.getBio()).isEqualTo("团队开发");
        assertThat(own.getRole()).isEqualTo(Role.DEVELOPER);
        assertThat(auth.authenticate("profile_own", "secret123").getId()).isEqualTo(own.getId());
        assertThat(users.findById(other.getId()).orElseThrow().getDisplayName()).isEqualTo("其他人");
        assertThat(session.getAttribute("displayName")).isEqualTo("新姓名");
        mvc.perform(get("/account").session(session)).andExpect(content().string(org.hamcrest.Matchers.containsString("test@example.com")));
    }

    @Test void rejectsUnauthenticatedAndMissingTokenWrites() throws Exception {
        mvc.perform(get("/account")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/account").param("displayName", "非法")).andExpect(redirectedUrl("/login"));
        var session = login("profile_token");
        mvc.perform(post("/account").session(session).param("displayName", "非法"))
                .andExpect(view().name("account")).andExpect(content().string(org.hamcrest.Matchers.containsString("页面已失效")));
        assertThat(users.findByUsername("profile_token").orElseThrow().getDisplayName()).isEqualTo("原姓名");
    }

    @Test void validatesFieldsWithoutChangingSavedData() throws Exception {
        var session = login("profile_validation");
        Long id = (Long)session.getAttribute("userId");
        assertThatThrownBy(() -> profiles.update(id, " ", "", "", "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> profiles.update(id, "正常", "bad-mail", "", "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> profiles.update(id, "正常", "", "not-phone", "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> profiles.update(id, "正常", "", "", "a".repeat(501))).isInstanceOf(BusinessException.class);
        assertThat(profiles.get(id).displayName()).isEqualTo("原姓名");
        mvc.perform(post("/account").session(session).param("csrfToken", (String)session.getAttribute("profileCsrfToken"))
                .param("displayName", "修改草稿").param("email", "bad-mail"))
                .andExpect(view().name("account")).andExpect(content().string(org.hamcrest.Matchers.containsString("修改草稿")));
    }

    @Test void optionalFieldsCanBeClearedAndHtmlIsEscaped() throws Exception {
        var session = login("profile_optional");
        Long id = (Long)session.getAttribute("userId");
        profiles.update(id, "原姓名", "a@example.com", "12345", "<script>alert(1)</script>");
        mvc.perform(get("/account").session(session)).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
        profiles.update(id, "原姓名", null, null, null);
        assertThat(profiles.get(id).email()).isEmpty();
        assertThat(profiles.get(id).phone()).isEmpty();
        assertThat(profiles.get(id).bio()).isEmpty();
    }
}
