package com.aimanage.prototype;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class WebFlowTest {

    @Autowired MockMvc mockMvc;

    @Test
    void managerCanLoginAndRenderDashboard() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "manager")
                        .param("password", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/manager").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("manager-dashboard"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("爱管理课程项目")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("完成登录页面")));
    }

    @Test
    void developerCanLoginAndRenderOwnTasks() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "developer")
                        .param("password", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/my-tasks"))
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/my-tasks").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("my-tasks"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("完成登录页面")));
    }

    @Test
    void authenticatedUserCanRenderEveryPreviewModule() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "manager")
                        .param("password", "123456"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/preview/account").session(session)).andExpect(redirectedUrl("/account"));
        mockMvc.perform(get("/account").session(session)).andExpect(status().isOk()).andExpect(view().name("account"));
        mockMvc.perform(get("/preview/permissions").session(session)).andExpect(redirectedUrl("/admin/roles"));
        String[] modules = {"projects", "backlog", "sprints", "tasks", "ai", "analytics"};
        for (String module : modules) {
            mockMvc.perform(get("/preview/" + module).session(session))
                    .andExpect(status().isOk())
                    .andExpect(view().name("prototype-page"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("功能清单")));
        }
    }

    @Test
    void wrongPasswordShouldRenderClearError() throws Exception {
        mockMvc.perform(post("/login")
                        .param("username", "manager")
                        .param("password", "wrong"))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("账号或密码错误")));
    }

    @Test
    void developerCannotOpenManagerDashboard() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "developer")
                        .param("password", "123456"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/manager").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void managerCannotOpenDeveloperTaskPage() throws Exception {
        MvcResult login = mockMvc.perform(post("/login")
                        .param("username", "manager")
                        .param("password", "123456"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mockMvc.perform(get("/my-tasks").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
