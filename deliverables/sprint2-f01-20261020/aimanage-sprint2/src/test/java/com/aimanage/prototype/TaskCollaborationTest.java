package com.aimanage.prototype;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TaskCollaborationTest {
    @Autowired TaskCollaborationService collaboration;
    @Autowired ProjectTaskService core;
    @Autowired UserRepository users;
    @Autowired TaskRepository tasks;
    @Autowired MockMvc mvc;
    @Autowired EntityManager em;
    User manager, developer, other, outsider;
    Project project;
    Task first, second;

    @BeforeEach void setUp() {
        manager = users.findByUsername("manager").orElseThrow();
        developer = users.findByUsername("developer").orElseThrow();
        outsider = users.findByUsername("developer2").orElseThrow();
        other = users.save(new User("collab_member", "123456", "协作成员", Role.DEVELOPER));
        project = core.createProject(manager.getId(), "协作测试", "");
        core.addMember(manager.getId(), project.getId(), developer.getId());
        core.addMember(manager.getId(), project.getId(), other.getId());
        first = core.createTask(manager.getId(), project.getId(), "登录界面", "实现登录", LocalDate.of(2026, 11, 3), developer.getId());
        second = core.createTask(manager.getId(), project.getId(), "测试接口", "接口测试", null, other.getId());
    }
    TaskCollaborationService.Filter filter(String keyword, Long assignee, TaskStatus status, Priority priority,
                                           LocalDate from, LocalDate to, Boolean blocked, String sort) {
        return new TaskCollaborationService.Filter(keyword, assignee, status, priority, from, to, blocked, sort);
    }
    MockHttpSession session(User user) {
        var session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", user.getRole());
        return session;
    }
    void flush() { em.flush(); em.clear(); }

    @Test void combinedFiltersAndSortUseRealTaskFields() {
        core.planTask(manager.getId(), first.getId(), Priority.URGENT, null);
        core.updateStatus(developer.getId(), first.getId(), TaskStatus.IN_PROGRESS);
        collaboration.block(developer.getId(), first.getId(), true, "等待联调");
        var result = collaboration.search(developer.getId(), project.getId(),
                filter("登录", developer.getId(), TaskStatus.IN_PROGRESS, Priority.URGENT,
                        LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 5), true, "due"));
        assertThat(result).extracting(TaskCollaborationService.TaskView::id).containsExactly(first.getId());
        assertThat(collaboration.search(manager.getId(), project.getId(), filter("", null, null, null, null, null, null, "due")))
                .extracting(TaskCollaborationService.TaskView::id).containsExactly(first.getId(), second.getId());
        assertThat(collaboration.search(manager.getId(), project.getId(), filter("", null, null, null, null, null, null, "priority")))
                .extracting(TaskCollaborationService.TaskView::id).containsExactly(first.getId(), second.getId());
    }

    @Test void rejectsInvalidQueryAndCrossProjectReads() {
        assertThatThrownBy(() -> collaboration.search(manager.getId(), project.getId(),
                filter("", null, null, null, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 1), null, "due")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.search(manager.getId(), project.getId(),
                filter("", null, null, null, null, null, null, "sql")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.search(outsider.getId(), project.getId(),
                filter("", null, null, null, null, null, null, "updated")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.detail(outsider.getId(), first.getId())).isInstanceOf(BusinessException.class);
    }

    @Test void commentsAndOldEntryPointsPersistJournal() {
        collaboration.comment(other.getId(), first.getId(), "团队讨论");
        core.editTask(manager.getId(), first.getId(), "新标题", "新说明", LocalDate.of(2026, 11, 4), other.getId());
        core.updateStatus(other.getId(), first.getId(), TaskStatus.DONE, "完成测试");
        core.planTask(manager.getId(), first.getId(), Priority.HIGH, null);
        Long id = first.getId();
        flush();
        assertThat(collaboration.detail(manager.getId(), id).entries())
                .extracting(TaskCollaborationService.EntryView::content)
                .contains("团队讨论", "修改任务描述", "状态：待处理 → 已完成；完成说明：完成测试", "优先级：MEDIUM → HIGH");
        assertThat(collaboration.detail(manager.getId(), id).task().assigneeId()).isEqualTo(other.getId());
    }

    @Test void rejectsEmptyAndLongComments() {
        assertThatThrownBy(() -> collaboration.comment(developer.getId(), first.getId(), " ")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.comment(developer.getId(), first.getId(), "x".repeat(2001))).isInstanceOf(BusinessException.class);
    }

    @Test void pausedMemberCanReadButCannotCommentOrBlockOrChangeStatus() {
        core.updateStatus(developer.getId(), first.getId(), TaskStatus.DONE, "完成工作");
        core.updateMember(manager.getId(), project.getId(), developer.getId(), MemberStatus.PAUSED, "开发", "");
        assertThat(collaboration.detail(developer.getId(), first.getId()).canComment()).isFalse();
        assertThatThrownBy(() -> collaboration.comment(developer.getId(), first.getId(), "越权")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.block(developer.getId(), first.getId(), true, "越权")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.status(developer.getId(), first.getId(), TaskStatus.DONE)).isInstanceOf(BusinessException.class);
    }

    @Test void blockingPersistsAndOnlyOwnerOrAssigneeMayWrite() {
        collaboration.block(developer.getId(), first.getId(), true, "等待接口");
        Long id = first.getId();
        flush();
        var detail = collaboration.detail(manager.getId(), id);
        assertThat(detail.task().blocked()).isTrue();
        assertThat(detail.task().blockedReason()).isEqualTo("等待接口");
        assertThatThrownBy(() -> collaboration.block(other.getId(), id, false, "")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.block(manager.getId(), id, true, "")).isInstanceOf(BusinessException.class);
        collaboration.block(manager.getId(), id, false, "");
        assertThat(tasks.findById(id).orElseThrow().getBlockedReason()).isEmpty();
    }

    @Test void dependencyPersistsAndCanBeRemoved() {
        collaboration.dependency(manager.getId(), second.getId(), first.getId(), false);
        Long a = first.getId(), b = second.getId();
        flush();
        assertThat(collaboration.detail(developer.getId(), b).predecessors())
                .extracting(TaskCollaborationService.TaskView::id).containsExactly(a);
        assertThat(collaboration.detail(developer.getId(), a).successors())
                .extracting(TaskCollaborationService.TaskView::id).containsExactly(b);
        collaboration.dependency(manager.getId(), b, a, true);
        assertThat(collaboration.detail(developer.getId(), b).predecessors()).isEmpty();
    }

    @Test void dependencyRejectsDuplicateSelfCycleAndUnauthorizedWrites() {
        var third = core.createTask(manager.getId(), project.getId(), "第三任务", "", null, developer.getId());
        collaboration.dependency(manager.getId(), second.getId(), first.getId(), false);
        collaboration.dependency(manager.getId(), third.getId(), second.getId(), false);
        assertThatThrownBy(() -> collaboration.dependency(manager.getId(), first.getId(), third.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("循环");
        assertThatThrownBy(() -> collaboration.dependency(manager.getId(), second.getId(), first.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已存在");
        assertThatThrownBy(() -> collaboration.dependency(manager.getId(), first.getId(), first.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("自身");
        assertThatThrownBy(() -> collaboration.dependency(developer.getId(), second.getId(), first.getId(), true))
                .isInstanceOf(BusinessException.class);
    }

    @Test void dependencyRejectsCrossProject() {
        var otherProject = core.createProject(manager.getId(), "其他项目", "");
        core.addMember(manager.getId(), otherProject.getId(), developer.getId());
        var foreign = core.createTask(manager.getId(), otherProject.getId(), "外部任务", "", null, developer.getId());
        assertThatThrownBy(() -> collaboration.dependency(manager.getId(), first.getId(), foreign.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("同一项目");
    }

    @Test void boardAndDetailRenderAndEscapeUserText() throws Exception {
        var s = session(developer);
        collaboration.comment(developer.getId(), first.getId(), "<script>alert(1)</script>");
        mvc.perform(get("/tasks").session(s).param("projectId", project.getId().toString()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("登录界面")));
        mvc.perform(get("/tasks/" + first.getId()).session(s))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
        var managerSession = session(manager);
        mvc.perform(get("/tasks/" + second.getId()).session(managerSession)).andExpect(status().isOk());
    }

    @Test void webWritesRequireCsrfAndRespectAssignee() throws Exception {
        var s = session(developer);
        mvc.perform(get("/tasks/" + first.getId()).session(s)).andExpect(status().isOk());
        mvc.perform(post("/tasks/" + first.getId() + "/comments").session(s).param("content", "缺少令牌"))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/tasks/" + first.getId() + "/comments").session(s)
                        .param("csrfToken", (String) s.getAttribute("taskCsrf")).param("content", "网页评论"))
                .andExpect(flash().attributeExists("success"));
        mvc.perform(post("/tasks/" + second.getId() + "/status").session(s)
                        .param("csrfToken", (String) s.getAttribute("taskCsrf")).param("status", "DONE"))
                .andExpect(flash().attributeExists("error"));
        assertThat(second.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(collaboration.detail(developer.getId(), first.getId()).entries())
                .extracting(TaskCollaborationService.EntryView::content).contains("网页评论").doesNotContain("缺少令牌");
    }

    @Test void anonymousAdminAndOutsiderCannotAccessTaskData() throws Exception {
        mvc.perform(get("/tasks")).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/tasks").session(session(users.findByUsername("admin").orElseThrow())))
                .andExpect(redirectedUrl("/login"));
        mvc.perform(get("/tasks/" + first.getId()).session(session(outsider)))
                .andExpect(redirectedUrl("/tasks")).andExpect(flash().attributeExists("error"));
        mvc.perform(get("/tasks").session(session(outsider)).param("projectId", project.getId().toString()))
                .andExpect(redirectedUrl("/tasks")).andExpect(flash().attributeExists("error"));
    }

    @Test void contextEndpointProvidesAuthorizedDataWithoutCredentials() throws Exception {
        mvc.perform(get("/tasks/" + first.getId() + "/context").session(session(developer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.task.title").value("登录界面"))
                .andExpect(jsonPath("$.task.blocked").value(false))
                .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.username").doesNotExist());
        mvc.perform(get("/tasks/" + first.getId() + "/context").session(session(outsider)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.task").doesNotExist());
    }
}
