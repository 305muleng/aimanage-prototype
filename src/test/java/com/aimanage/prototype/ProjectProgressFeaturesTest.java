package com.aimanage.prototype;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.UserRepository;
import com.aimanage.prototype.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
class ProjectProgressFeaturesTest {
    @Autowired ProjectTaskService projects;
    @Autowired ReminderService reminders;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;

    private MockHttpSession session(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("userId", user.getId());
        session.setAttribute("role", user.getRole());
        session.setAttribute("displayName", user.getDisplayName());
        return session;
    }

    @Test void statusHistoryReschedulingAndRemindersRespectRolesAndVersions() {
        User manager = users.findByUsername("manager").orElseThrow();
        User developer = users.findByUsername("developer").orElseThrow();
        Project project = projects.createProject(manager.getId(), "进展测试 " + UUID.randomUUID(), "");
        projects.addMember(manager.getId(), project.getId(), developer.getId());
        Task task = projects.createTask(manager.getId(), project.getId(), "提醒测试", "", LocalDate.now().plusDays(2), developer.getId());
        assertThat(projects.projectStatusHistory(manager.getId(), project.getId()))
                .extracting(change -> change.getNextStatus()).containsExactly(ProjectStatus.ACTIVE, ProjectStatus.PLANNED);
        assertThatThrownBy(() -> projects.projectStatusHistory(developer.getId(), project.getId()))
                .isInstanceOf(BusinessException.class);
        assertThat(reminders.unread(developer.getId())).anyMatch(item -> item.task().getId().equals(task.getId()));
        reminders.markRead(developer.getId(), task.getId());
        assertThat(reminders.unread(developer.getId())).noneMatch(item -> item.task().getId().equals(task.getId()));

        projects.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.PAUSED, "等待需求确认");
        assertThat(projects.projectStatusHistory(manager.getId(), project.getId()).get(0).getReason()).isEqualTo("等待需求确认");
        assertThatThrownBy(() -> projects.rescheduleTask(manager.getId(), task.getId(), LocalDate.now().plusDays(4), task.getVersion()))
                .isInstanceOf(BusinessException.class);
        projects.changeProjectStatus(manager.getId(), project.getId(), ProjectStatus.ACTIVE, "恢复执行");
        long version = task.getVersion();
        assertThatThrownBy(() -> projects.rescheduleTask(developer.getId(), task.getId(), LocalDate.now().plusDays(4), version))
                .isInstanceOf(BusinessException.class);
        projects.rescheduleTask(manager.getId(), task.getId(), LocalDate.now().plusDays(1), version);
        assertThat(reminders.unread(developer.getId())).anyMatch(item -> item.task().getId().equals(task.getId()));
        assertThatThrownBy(() -> projects.rescheduleTask(manager.getId(), task.getId(), LocalDate.now().plusDays(5), version))
                .isInstanceOf(BusinessException.class);
    }

    @Test void shareAndCalendarPagesRenderForManagerOnly() throws Exception {
        User manager = users.findByUsername("manager").orElseThrow();
        User developer = users.findByUsername("developer").orElseThrow();
        mvc.perform(get("/manager").session(session(manager)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("分享进展图片")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("share-progress-canvas")));
        mvc.perform(get("/manager/calendar").session(session(manager)).param("view", "week"))
                .andExpect(status().isOk())
                .andExpect(view().name("manager-calendar"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("负责人任务截止日期日历")));
        mvc.perform(get("/manager/calendar").session(session(developer)))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/my-tasks").session(session(developer)).param("view", "week"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("上周")));
    }
}
