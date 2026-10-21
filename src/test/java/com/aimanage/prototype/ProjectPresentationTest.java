package com.aimanage.prototype;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.TaskStatus;
import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.repository.ProjectRepository;
import com.aimanage.prototype.repository.TaskRepository;
import com.aimanage.prototype.repository.UserRepository;
import com.aimanage.prototype.service.ProjectTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ProjectPresentationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired ProjectRepository projects;
    @Autowired TaskRepository tasks;
    @Autowired ProjectTaskService service;

    MockHttpSession session(User user) {
        var session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", user.getRole());
        session.setAttribute("displayName", user.getDisplayName());
        return session;
    }

    @Test void managerCanOpenProjectsAndAddMemberThere() throws Exception {
        User manager = users.findByUsername("manager").orElseThrow();
        User member = users.save(new User("present-" + java.util.UUID.randomUUID(), "123456", "演示新成员", Role.DEVELOPER));
        var project = service.createProject(manager.getId(), "成员页面测试", "团队演示");
        var session = session(manager);
        mvc.perform(get("/projects").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("manager-projects"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("成员页面测试")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("添加成员")));
        String token = (String) session.getAttribute("projectCsrf");
        mvc.perform(post("/manager/projects/" + project.getId() + "/members").session(session)
                        .param("userId", member.getId().toString()).param("csrfToken", token)
                        .param("returnToProjects", "true"))
                .andExpect(redirectedUrl("/projects"));
        mvc.perform(get("/projects").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("演示新成员")));
        mvc.perform(get("/projects").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("编辑 演示新成员")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("移除 演示新成员")));
        mvc.perform(post("/manager/projects/" + project.getId() + "/members/" + member.getId() + "/edit")
                        .session(session).param("csrfToken", token).param("returnToProjects", "true")
                        .param("status", "PAUSED").param("projectIdentity", "测试人员")
                        .param("responsibilities", "验收"))
                .andExpect(redirectedUrl("/projects"));
        mvc.perform(get("/projects").session(session))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("测试人员")));
        mvc.perform(post("/manager/projects/" + project.getId() + "/members/" + member.getId() + "/remove")
                        .session(session).param("csrfToken", token).param("returnToProjects", "true"))
                .andExpect(redirectedUrl("/projects"));
    }

    @Test void developerTaskFiltersStayWithinOwnTasks() throws Exception {
        User manager = users.findByUsername("manager").orElseThrow();
        User developer = users.findByUsername("developer").orElseThrow();
        User other = users.findByUsername("developer2").orElseThrow();
        var project = service.createProject(manager.getId(), "筛选项目", "");
        service.addMember(manager.getId(), project.getId(), developer.getId());
        service.addMember(manager.getId(), project.getId(), other.getId());
        var own = service.createTask(manager.getId(), project.getId(), "本人筛选任务", "", null, developer.getId());
        service.planTask(manager.getId(), own.getId(), com.aimanage.prototype.domain.Priority.HIGH, null);
        service.createTask(manager.getId(), project.getId(), "他人任务不可见", "", null, other.getId());
        mvc.perform(get("/my-tasks").session(session(developer)).param("projectId", project.getId().toString())
                        .param("priority", "HIGH").param("sort", "priority"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("本人筛选任务")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("他人任务不可见"))));
    }

    @Test void managerCanFilterProjectsAndDeveloperCanOpenRealCalendar() throws Exception {
        User manager = users.findByUsername("manager").orElseThrow();
        var planned = service.createProject(manager.getId(), "状态筛选演示", "");
        mvc.perform(get("/manager").session(session(manager)).param("status", "PLANNED")
                        .param("keyword", "状态筛选").param("sort", "name"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("状态筛选演示")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("规划中")));
        User developer = users.findByUsername("developer").orElseThrow();
        mvc.perform(get("/my-tasks").session(session(developer)).param("view", "calendar")
                        .param("month", "2026-10"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("任务截止日期日历")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("上个月")));
        assertThat(planned.getStatus()).isEqualTo(com.aimanage.prototype.domain.ProjectStatus.PLANNED);
    }

    @Test void taskBoardShowsScrollableProjectsAndClickableSquareCards() throws Exception {
        User manager = users.findByUsername("manager").orElseThrow();
        var project = service.createProject(manager.getId(), "第二演示项目", "");
        User developer = users.findByUsername("developer").orElseThrow();
        service.addMember(manager.getId(), project.getId(), developer.getId());
        var task = service.createTask(manager.getId(), project.getId(), "可打开的任务", "", null, developer.getId());
        mvc.perform(get("/tasks").param("projectId", project.getId().toString()).session(session(manager)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("project-rail")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("第二演示项目")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("task-card-link")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/tasks/" + task.getId())));
    }

    @Test void freshDemoDataUsesNeutralProjectWithSeveralTasks() {
        User manager = users.findByUsername("manager").orElseThrow();
        var seeded = projects.findByOwnerIdOrderByCreatedAtDesc(manager.getId()).stream()
                .filter(project -> project.getName().equals("团队协作演示项目")).findFirst().orElseThrow();
        assertThat(projects.findByOwnerIdOrderByCreatedAtDesc(manager.getId()))
                .extracting(project -> project.getName())
                .contains("移动端体验演示项目", "团队协作演示项目");
        assertThat(tasks.findByProjectIdOrderByUpdatedAtDesc(seeded.getId())).hasSizeGreaterThanOrEqualTo(5);
        assertThat(tasks.findByProjectIdOrderByUpdatedAtDesc(seeded.getId()))
                .extracting(task -> task.getStatus()).contains(TaskStatus.IN_PROGRESS, TaskStatus.DONE);
        assertThat(users.findByUsername("developer3")).isPresent();
        assertThat(projects.findByOwnerIdOrderByCreatedAtDesc(manager.getId()))
                .noneMatch(project -> project.getName().equals("爱管理课程项目"));
    }
}
