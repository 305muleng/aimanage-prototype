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
        String[] modules = {"account", "projects", "permissions", "backlog", "sprints", "tasks", "ai", "analytics"};
        for (String module : modules) {
            mockMvc.perform(get("/preview/" + module).session(session))
                    .andExpect(status().isOk())
                    .andExpect(view().name("prototype-page"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            module.equals("ai") ? "候选决策" : "功能清单")));
        }
    }

    @Test
    void aiPageUsesLoggedInRoleAndLoadsOnlyFrontendAssets() throws Exception {
        for (String username : new String[]{"manager", "developer"}) {
            MvcResult login = mockMvc.perform(post("/login")
                            .param("username", username).param("password", "123456"))
                    .andExpect(status().is3xxRedirection()).andReturn();
            MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
            mockMvc.perform(get("/preview/ai").session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString(
                            "data-can-review=\"" + username.equals("manager") + "\"")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("/js/ai-workspace.js")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("审核理由（必填）")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.matchesPattern("(?s).*<textarea[^>]*id=\"ai-question\"[^>]*\\sdisabled\\b.*"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("演示模式"))))
                    .andExpect(content().string(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("配置 AI 智能体"))));
        }
    }

    @Test
    void anonymousVisitorCannotOpenAiPage() throws Exception {
        mockMvc.perform(get("/preview/ai"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void aiAssetsUseHttpOnlyAndDoNotExposeDemoData() throws Exception {
        mockMvc.perform(get("/js/ai-api.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/api/ai/workspace")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("createDemoAiApi"))));
        mockMvc.perform(get("/js/ai-demo-data.js"))
                .andExpect(status().isNotFound());
    }
}
