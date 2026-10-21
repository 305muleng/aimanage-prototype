package com.aimanage.prototype.config;

import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import com.aimanage.prototype.service.PasswordCodec;

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
    private final TaskEntryRepository taskEntries;
    private final ProjectStatusChangeRepository statusChanges;
    private final boolean demoEnabled;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final com.aimanage.prototype.service.AuthService authService;
    private final com.aimanage.prototype.service.RoleManagementService roleService;

    public DemoDataInitializer(UserRepository userRepository, ProjectRepository projectRepository,
                                ProjectMemberRepository memberRepository, TaskRepository taskRepository,
                                TaskEntryRepository taskEntries, ProjectStatusChangeRepository statusChanges,
                                @Value("${aimanage.demo.enabled:false}") boolean demoEnabled,
                                @Value("${aimanage.bootstrap.admin.username:}") String bootstrapUsername,
                                @Value("${aimanage.bootstrap.admin.password:}") String bootstrapPassword,
                               com.aimanage.prototype.service.AuthService authService,
                               com.aimanage.prototype.service.RoleManagementService roleService) {
        this.userRepository = userRepository;
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.taskRepository = taskRepository;
        this.taskEntries = taskEntries;
        this.statusChanges = statusChanges;
        this.demoEnabled = demoEnabled;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.authService = authService;
        this.roleService = roleService;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void run(String... args) {
        roleService.initializeBuiltIns();
        if (!demoEnabled) {
            roleService.linkExistingUsers();
            authService.ensureBootstrapAdministrator(bootstrapUsername, bootstrapPassword);
            return;
        }
        if (userRepository.count() > 0) {
            authService.ensureDemoAdministrator();
            roleService.linkExistingUsers();
            for (Project project : projectRepository.findAll()) {
                if (statusChanges.findByProjectIdOrderByChangedAtDescIdDesc(project.getId()).isEmpty())
                    statusChanges.save(new ProjectStatusChange(project, project.getOwner(), null,
                            project.getStatus(), "历史状态基线；此前变化不可追溯"));
            }
            for (Task task : taskRepository.findAll()) {
                if (taskEntries.findByTaskIdOrderByCreatedAtDescIdDesc(task.getId()).isEmpty())
                    seedEntry(task, task.getProject().getOwner(), "历史任务导入；原始操作记录不可用");
            }
            return;
        }

        User manager = userRepository.save(new User("manager", PasswordCodec.encode("123456"), "陈项目", Role.PROJECT_MANAGER));
        User developer = userRepository.save(new User("developer", PasswordCodec.encode("123456"), "李开发", Role.DEVELOPER));
        User developer2 = userRepository.save(new User("developer2", PasswordCodec.encode("123456"), "王开发", Role.DEVELOPER));
        userRepository.save(new User("developer3", PasswordCodec.encode("123456"), "赵开发", Role.DEVELOPER));

        Project project = projectRepository.save(new Project(
                "团队协作演示项目", "展示项目成员、任务看板与协作讨论的示例项目。", manager));
        statusChanges.save(new ProjectStatusChange(project, manager, null, ProjectStatus.PLANNED, "创建演示项目"));
        project.changeStatus(ProjectStatus.ACTIVE);
        statusChanges.save(new ProjectStatusChange(project, manager, ProjectStatus.PLANNED, ProjectStatus.ACTIVE, "导入演示任务"));
        memberRepository.save(new ProjectMember(project, developer));
        memberRepository.save(new ProjectMember(project, developer2));
        Task design = taskRepository.save(new Task("梳理首页需求", "确定首页信息架构与关键内容。",
                LocalDate.now().plusDays(2), project, developer));
        seedEntry(design, manager, "导入演示任务");
        design.changeStatus(TaskStatus.IN_PROGRESS);
        taskRepository.save(design);
        seedEntry(design, developer, "演示任务状态：进行中");
        seedTask(new Task("制作项目看板", "设计任务卡片和项目切换体验。",
                LocalDate.now().plusDays(5), project, developer2), manager);
        seedTask(new Task("实现成员管理", "支持负责人添加成员并查看团队分工。",
                LocalDate.now().plusDays(6), project, developer), manager);
        seedTask(new Task("编写接口测试", "覆盖任务查询、详情和权限边界。",
                LocalDate.now().plusDays(7), project, developer2), manager);
        seedTask(new Task("准备演示讲解", "整理演示步骤和角色账号。",
                LocalDate.now().plusDays(8), project, developer), manager);
        Task completed = taskRepository.save(new Task("创建项目空间", "建立演示项目及初始成员。",
                LocalDate.now().minusDays(1), project, developer2));
        seedEntry(completed, manager, "导入演示任务");
        completed.complete("演示项目已创建");
        taskRepository.save(completed);
        seedEntry(completed, developer2, "演示任务已完成：演示项目已创建");
        Project mobileProject = projectRepository.save(new Project(
                "移动端体验演示项目", "用于展示多个项目之间的切换与横向浏览。", manager));
        statusChanges.save(new ProjectStatusChange(mobileProject, manager, null, ProjectStatus.PLANNED, "创建演示项目"));
        mobileProject.changeStatus(ProjectStatus.ACTIVE);
        statusChanges.save(new ProjectStatusChange(mobileProject, manager, ProjectStatus.PLANNED, ProjectStatus.ACTIVE, "导入演示任务"));
        memberRepository.save(new ProjectMember(mobileProject, developer2));
        seedTask(new Task("梳理移动端导航", "检查窄屏下的导航与项目切换。",
                LocalDate.now().plusDays(4), mobileProject, developer2), manager);
        seedTask(new Task("验证任务卡片", "在手机视口检查任务卡片的阅读和点击。",
                LocalDate.now().plusDays(6), mobileProject, developer2), manager);
        authService.ensureDemoAdministrator();
        roleService.linkExistingUsers();
    }
    private void seedTask(Task task, User manager) {
        seedEntry(taskRepository.save(task), manager, "导入演示任务");
    }
    private void seedEntry(Task task, User actor, String content) {
        taskEntries.save(new TaskEntry(task, actor, "CHANGE", content));
    }
}
