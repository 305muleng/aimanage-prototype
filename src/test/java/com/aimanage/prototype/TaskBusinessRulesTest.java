package com.aimanage.prototype;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import com.aimanage.prototype.service.*;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class TaskBusinessRulesTest {
    @Autowired ProjectTaskService core;
    @Autowired TaskCollaborationService collaboration;
    @Autowired UserRepository users;
    @Autowired TaskRepository tasks;
    @Autowired ProjectRepository projects;
    @Autowired ProjectMemberRepository members;
    @Autowired EntityManagerFactory factory;
    @Autowired MockMvc mvc;
    User manager, developer, other;
    Project project;
    Task task;

    @BeforeEach void setup() {
        String suffix = UUID.randomUUID().toString();
        manager = users.save(new User("m" + suffix, "123456", "负责人", Role.PROJECT_MANAGER));
        developer = users.save(new User("d" + suffix, "123456", "开发甲", Role.DEVELOPER));
        other = users.save(new User("o" + suffix, "123456", "开发乙", Role.DEVELOPER));
        project = core.createProject(manager.getId(), "规则验证", "");
        core.addMember(manager.getId(), project.getId(), developer.getId());
        core.addMember(manager.getId(), project.getId(), other.getId());
        task = core.createTask(manager.getId(), project.getId(), "任务", "说明", null, developer.getId());
    }
    MockHttpSession session(User user) {
        var s = new MockHttpSession();
        s.setAttribute("userId", user.getId()); s.setAttribute("role", user.getRole());
        return s;
    }
    Task current() { return tasks.findById(task.getId()).orElseThrow(); }
    void complete() { core.updateStatus(developer.getId(), task.getId(), TaskStatus.DONE, "测试通过"); }

    @Test void taskLengthsAreCheckedOnBothCreateAndEdit() {
        assertThatThrownBy(() -> core.createTask(manager.getId(), project.getId(), "x".repeat(121), "", null, developer.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("120");
        assertThatThrownBy(() -> core.createTask(manager.getId(), project.getId(), "合法", "x".repeat(1001), null, developer.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("1000");
        assertThatThrownBy(() -> core.editTask(manager.getId(), task.getId(), "x".repeat(121), "", null, developer.getId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> core.editTask(manager.getId(), task.getId(), "合法", "x".repeat(1001), null, developer.getId()))
                .isInstanceOf(BusinessException.class);
        core.editTask(manager.getId(), task.getId(), "x".repeat(120), "x".repeat(1000), null, developer.getId());
        assertThat(current().getDescription()).hasSize(1000);
    }
    @Test void completionAndReopeningRequireNotesAndKeepHistory() {
        assertThatThrownBy(() -> core.updateStatus(developer.getId(), task.getId(), TaskStatus.DONE))
                .isInstanceOf(BusinessException.class).hasMessageContaining("完成说明");
        complete();
        assertThat(current().getCompletedAt()).isNotNull();
        assertThat(current().getCompletionNote()).isEqualTo("测试通过");
        assertThatThrownBy(() -> core.updateStatus(developer.getId(), task.getId(), TaskStatus.TODO))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重开原因");
        core.updateStatus(developer.getId(), task.getId(), TaskStatus.TODO, "发现遗漏");
        assertThat(current().getCompletedAt()).isNull();
        assertThat(current().getCompletionNote()).isEmpty();
        assertThat(collaboration.detail(manager.getId(), task.getId()).entries())
                .extracting(TaskCollaborationService.EntryView::content)
                .anyMatch(s -> s.contains("完成说明：测试通过")).anyMatch(s -> s.contains("重开原因：发现遗漏"));
    }
    @Test void blockedTaskCannotCompleteAndCompletedTaskCannotBeBlocked() {
        collaboration.block(developer.getId(), task.getId(), true, "等待确认");
        assertThatThrownBy(this::complete).isInstanceOf(BusinessException.class).hasMessageContaining("阻塞");
        collaboration.block(manager.getId(), task.getId(), false, "");
        complete();
        assertThatThrownBy(() -> collaboration.block(manager.getId(), task.getId(), true, "新问题"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重开");
    }
    @Test void dependenciesGateCompletionAndPreventInconsistentReopening() {
        Task predecessor = core.createTask(manager.getId(), project.getId(), "前置", "", null, other.getId());
        collaboration.dependency(manager.getId(), task.getId(), predecessor.getId(), false);
        core.updateStatus(developer.getId(), task.getId(), TaskStatus.IN_PROGRESS);
        assertThatThrownBy(this::complete).isInstanceOf(BusinessException.class).hasMessageContaining("前置");
        core.updateStatus(other.getId(), predecessor.getId(), TaskStatus.DONE, "完成前置");
        complete();
        assertThatThrownBy(() -> core.updateStatus(other.getId(), predecessor.getId(), TaskStatus.IN_PROGRESS, "修复前置"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("后续任务");
        assertThat(current().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(collaboration.detail(manager.getId(), task.getId()).predecessors().get(0).status()).isEqualTo(TaskStatus.DONE);
    }
    @Test void completedTaskMustReopenBeforeContentReassignmentOrNewDependency() {
        complete();
        assertThatThrownBy(() -> core.editTask(manager.getId(), task.getId(), "新任务", "说明", null, developer.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重开");
        assertThatThrownBy(() -> core.editTask(manager.getId(), task.getId(), "任务", "说明", null, other.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重开");
        Task predecessor = core.createTask(manager.getId(), project.getId(), "前置", "", null, other.getId());
        assertThatThrownBy(() -> collaboration.dependency(manager.getId(), task.getId(), predecessor.getId(), false))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重开");
        core.updateStatus(manager.getId(), task.getId(), TaskStatus.TODO, "调整范围");
        core.editTask(manager.getId(), task.getId(), "新任务", "新说明", null, other.getId());
        assertThat(current().getAssignee().getId()).isEqualTo(other.getId());
    }
    @Test void memberHandoffPreservesCompletedOwnershipAndAllowsOwnerReopen() throws Exception {
        assertThatThrownBy(() -> core.updateMember(manager.getId(), project.getId(), developer.getId(), MemberStatus.PAUSED, "开发", ""))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未完成");
        assertThatThrownBy(() -> core.removeMember(manager.getId(), project.getId(), developer.getId()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未完成");
        complete();
        core.updateMember(manager.getId(), project.getId(), developer.getId(), MemberStatus.PAUSED, "开发", "");
        core.removeMember(manager.getId(), project.getId(), developer.getId());
        assertThat(current().getAssignee().getId()).isEqualTo(developer.getId());
        assertThat(members.existsByProjectIdAndUserId(project.getId(), developer.getId())).isFalse();
        assertThat(collaboration.detail(developer.getId(), task.getId()).canComment()).isFalse();
        assertThat(core.myTasks(developer.getId())).extracting(Task::getId).contains(task.getId());
        mvc.perform(get("/manager").session(session(manager))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("历史负责人")));
        core.updateStatus(manager.getId(), task.getId(), TaskStatus.TODO, "重新安排");
        core.editTask(manager.getId(), task.getId(), "任务", "说明", null, other.getId());
        assertThat(current().getAssignee().getId()).isEqualTo(other.getId());
    }
    @Test void managerMayReopenButCannotCompleteAndOtherMemberCannotReopen() {
        assertThatThrownBy(() -> core.updateStatus(manager.getId(), task.getId(), TaskStatus.DONE, "代改"))
                .isInstanceOf(BusinessException.class);
        complete();
        assertThatThrownBy(() -> core.updateStatus(other.getId(), task.getId(), TaskStatus.TODO, "越权"))
                .isInstanceOf(BusinessException.class);
        core.updateStatus(manager.getId(), task.getId(), TaskStatus.IN_PROGRESS, "需修复");
        assertThat(current().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
    }
    @Test void projectStatusIsPersistedAndControlsTaskWork() {
        assertThat(projects.findById(project.getId()).orElseThrow().getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThatThrownBy(() -> core.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.COMPLETED))
                .isInstanceOf(BusinessException.class).hasMessageContaining("所有任务完成");
        core.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.PAUSED);
        assertThatThrownBy(() -> core.updateStatus(developer.getId(), task.getId(), TaskStatus.IN_PROGRESS))
                .isInstanceOf(BusinessException.class).hasMessageContaining("项目已暂停");
        assertThatThrownBy(() -> core.createTask(manager.getId(), project.getId(), "不应创建", "", null, developer.getId()))
                .isInstanceOf(BusinessException.class);
        core.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.ACTIVE);
        complete();
        core.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.COMPLETED);
        assertThatThrownBy(() -> core.updateStatus(manager.getId(), task.getId(), TaskStatus.TODO, "重开"))
                .isInstanceOf(BusinessException.class);
        core.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.ACTIVE);
        core.updateStatus(manager.getId(), task.getId(), TaskStatus.TODO, "重新规划");
    }
    @Test void stalePageCannotEditPlanBlockOrChangeStatus() {
        long oldVersion = current().getVersion();
        core.editTask(manager.getId(), task.getId(), "最新标题", "说明", null, developer.getId(), oldVersion);
        assertThatThrownBy(() -> core.editTask(manager.getId(), task.getId(), "覆盖", "说明", null, developer.getId(), oldVersion))
                .isInstanceOf(BusinessException.class).hasMessageContaining("刷新");
        assertThatThrownBy(() -> core.planTask(manager.getId(), task.getId(), Priority.HIGH, null, oldVersion))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> collaboration.block(developer.getId(), task.getId(), true, "覆盖", oldVersion))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> core.updateStatus(developer.getId(), task.getId(), TaskStatus.IN_PROGRESS, oldVersion))
                .isInstanceOf(BusinessException.class);
        assertThat(current().getTitle()).isEqualTo("最新标题");
        assertThat(current().getStatus()).isEqualTo(TaskStatus.TODO);
    }
    @Test void overlappingTransactionsCannotOverwriteEachOther() {
        var a = factory.createEntityManager(); var b = factory.createEntityManager();
        try {
            a.getTransaction().begin(); b.getTransaction().begin();
            Task first = a.find(Task.class, task.getId()), stale = b.find(Task.class, task.getId());
            first.changeStatus(TaskStatus.IN_PROGRESS);
            a.getTransaction().commit();
            stale.setBlock(true, "旧数据");
            assertThatThrownBy(() -> b.getTransaction().commit()).isInstanceOf(jakarta.persistence.RollbackException.class);
            assertThat(current().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
            assertThat(current().isBlocked()).isFalse();
        } finally {
            if (a.getTransaction().isActive()) a.getTransaction().rollback();
            if (b.getTransaction().isActive()) b.getTransaction().rollback();
            a.close(); b.close();
        }
    }
    @Test void oldManagerWriteRoutesRequireTokensAndValidFormsStillWork() throws Exception {
        var s = session(manager); mvc.perform(get("/manager").session(s)).andExpect(status().isOk());
        String token = (String) s.getAttribute("projectCsrf");
        mvc.perform(post("/manager/projects").session(s).param("name", "无令牌")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/manager/projects/" + project.getId() + "/members").session(s).param("userId", other.getId().toString()))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/manager/projects/" + project.getId() + "/tasks").session(s)
                .param("title", "无令牌").param("assigneeId", developer.getId().toString())).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/manager/tasks/" + task.getId() + "/edit").session(s)
                .param("title", "无令牌").param("assigneeId", developer.getId().toString())).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/manager/tasks/" + task.getId() + "/edit").session(s).param("csrfToken", token)
                .param("version", Long.toString(current().getVersion())).param("title", "网页修改").param("description", "说明")
                .param("assigneeId", developer.getId().toString())).andExpect(flash().attributeExists("success"));
        assertThat(current().getTitle()).isEqualTo("网页修改");
        mvc.perform(post("/manager/projects").session(s).param("name", "合法").param("csrfToken", token))
                .andExpect(flash().attributeExists("success"));
    }
    @Test void bothStatusEntryPointsRequireNoteTokenAndFreshVersion() throws Exception {
        var s = session(developer); mvc.perform(get("/my-tasks").session(s)).andExpect(status().isOk());
        mvc.perform(post("/my-tasks/" + task.getId() + "/status").session(s).param("status", "DONE"))
                .andExpect(flash().attributeExists("error"));
        String token = (String) s.getAttribute("myTasksCsrf");
        mvc.perform(post("/my-tasks/" + task.getId() + "/status").session(s).param("csrfToken", token)
                .param("version", Long.toString(current().getVersion())).param("status", "DONE"))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/my-tasks/" + task.getId() + "/status").session(s).param("csrfToken", token)
                .param("version", Long.toString(current().getVersion())).param("status", "DONE").param("note", "网页验证通过"))
                .andExpect(flash().attributeExists("success"));
        mvc.perform(get("/tasks/" + task.getId()).session(s)).andExpect(status().isOk());
        mvc.perform(post("/tasks/" + task.getId() + "/status").session(s).param("csrfToken", (String)s.getAttribute("taskCsrf"))
                .param("version", Long.toString(current().getVersion())).param("status", "TODO").param("note", "重新测试"))
                .andExpect(flash().attributeExists("success"));
        assertThat(current().getStatus()).isEqualTo(TaskStatus.TODO);
    }
}
