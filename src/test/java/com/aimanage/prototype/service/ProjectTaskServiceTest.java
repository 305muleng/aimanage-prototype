package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ProjectTaskServiceTest {

    @Autowired ProjectTaskService service;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ProjectMemberRepository memberRepository;
    @Autowired TaskRepository taskRepository;

    private User manager;
    private User developer;
    private User outsider;

    @BeforeEach
    void setUp() {
        taskRepository.deleteAll();
        memberRepository.deleteAll();
        projectRepository.deleteAll();
        userRepository.deleteAll();
        manager = userRepository.save(new User("manager-test", "123456", "测试负责人", Role.PROJECT_MANAGER));
        developer = userRepository.save(new User("dev-test", "123456", "测试开发", Role.DEVELOPER));
        outsider = userRepository.save(new User("outsider", "123456", "项目外成员", Role.DEVELOPER));
    }

    @Test
    void shouldAuthenticateValidAccountAndRejectWrongPassword() {
        assertThat(authService.authenticate("manager-test", "123456").getRole())
                .isEqualTo(Role.PROJECT_MANAGER);
        assertThatThrownBy(() -> authService.authenticate("manager-test", "wrong"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void shouldCreateProjectAndPreventDuplicateMember() {
        Project project = service.createProject(manager.getId(), "测试项目", "项目描述");
        service.addMember(manager.getId(), project.getId(), developer.getId());

        assertThat(service.members(project.getId())).hasSize(1);
        assertThatThrownBy(() -> service.addMember(manager.getId(), project.getId(), developer.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能重复添加");
    }

    @Test
    void shouldOnlyAssignTaskToProjectMember() {
        Project project = service.createProject(manager.getId(), "测试项目", "项目描述");
        service.addMember(manager.getId(), project.getId(), developer.getId());

        Task task = service.createTask(manager.getId(), project.getId(), "实现页面", "完成页面开发",
                LocalDate.now().plusDays(2), developer.getId());
        assertThat(task.getAssignee().getId()).isEqualTo(developer.getId());

        assertThatThrownBy(() -> service.createTask(manager.getId(), project.getId(), "非法分配", "",
                null, outsider.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须是该项目成员");
    }

    @Test
    void shouldOnlyAllowAssigneeToUpdateTaskStatus() {
        Project project = service.createProject(manager.getId(), "测试项目", "项目描述");
        service.addMember(manager.getId(), project.getId(), developer.getId());
        Task task = service.createTask(manager.getId(), project.getId(), "编写测试", "覆盖关键规则",
                LocalDate.now().plusDays(1), developer.getId());

        service.updateStatus(developer.getId(), task.getId(), TaskStatus.IN_PROGRESS);
        assertThat(taskRepository.findById(task.getId()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.IN_PROGRESS);

        assertThatThrownBy(() -> service.updateStatus(outsider.getId(), task.getId(), TaskStatus.DONE))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("自己的任务");
    }
}
