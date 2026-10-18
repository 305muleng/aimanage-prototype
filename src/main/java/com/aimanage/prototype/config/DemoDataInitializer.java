package com.aimanage.prototype.config;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 首次启动时创建课堂演示账号、示例项目和示例任务。
 *
 * <p>数据库已有用户时不会重复写入，因此文件数据库重启后可保留演示数据。</p>
 */
@Component
public class DemoDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final TaskRepository taskRepository;
    private final com.aimanage.prototype.service.AuthService authService;
    private final com.aimanage.prototype.service.RoleManagementService roleService;

    public DemoDataInitializer(UserRepository userRepository, ProjectRepository projectRepository,
                               ProjectMemberRepository memberRepository, TaskRepository taskRepository,
                               com.aimanage.prototype.service.AuthService authService,
                               com.aimanage.prototype.service.RoleManagementService roleService) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
        this.authService = authService;
        this.roleService = roleService;
    }

    @Override
    public void run(String... args) {
        roleService.initializeBuiltIns();
        if (userRepository.count() > 0) {
            authService.ensureDemoAdministrator();
            roleService.linkExistingUsers();
            return;
        }

        User manager = userRepository.save(new User("manager", "123456", "陈项目", Role.PROJECT_MANAGER));
        User developer = userRepository.save(new User("developer", "123456", "李开发", Role.DEVELOPER));
        userRepository.save(new User("developer2", "123456", "王开发", Role.DEVELOPER));

        Project project = projectRepository.save(new Project(
                "爱管理课程项目", "用于演示项目、成员、任务协作闭环的 Sprint 1 示例项目。", manager));
        memberRepository.save(new ProjectMember(project, developer));
        taskRepository.save(new Task(
                "完成登录页面", "实现项目负责人和开发人员的统一登录入口。",
                LocalDate.now().plusDays(3), project, developer));
        authService.ensureDemoAdministrator();
        roleService.linkExistingUsers();
    }
}
