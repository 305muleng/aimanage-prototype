package com.aimanage.prototype.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class PreviewController {

    private static final Map<String, PageSpec> PAGES = buildPages();

    @GetMapping("/preview/{slug}")
    public String preview(@PathVariable String slug, HttpSession session, Model model) {
        if (session.getAttribute(SessionKeys.USER_ID) == null) {
            return "redirect:/login";
        }
        PageSpec page = PAGES.get(slug);
        if (page == null) {
            return "redirect:/preview/account";
        }
        model.addAttribute("displayName", session.getAttribute(SessionKeys.DISPLAY_NAME));
        Object role = session.getAttribute(SessionKeys.ROLE);
        model.addAttribute("roleLabel", role == null ? "原型访客" : ((com.aimanage.prototype.domain.Role) role).getLabel());
        model.addAttribute("page", page);
        model.addAttribute("previewPages", PAGES.values());
        return "prototype-page";
    }

    private static Map<String, PageSpec> buildPages() {
        Map<String, PageSpec> pages = new LinkedHashMap<>();
        pages.put("account", new PageSpec("account", "账户与个人资料", "账号访问、个人资料与身份信息界面。", "用户",
                List.of(
                        f("Epic1-US01", "用户注册", "创建开发人员账号并进入平台。", "注册账号", false),
                        f("Epic1-US02", "用户登录", "开发人员登录并访问参与的项目与任务。", "已实现", true),
                        f("Epic1-US03", "项目负责人登录", "负责人登录并进入项目管理空间。", "已实现", true),
                        f("Epic1-US04", "系统管理员登录", "管理员进入用户、角色与权限管理空间。", "管理员入口", false),
                        f("Epic1-US05", "个人信息查看", "查看账号、角色、联系方式与项目身份。", "查看资料", false),
                        f("Epic1-US06", "个人信息修改", "维护姓名、联系方式和个人简介。", "编辑资料", false)
                )));
        pages.put("projects", new PageSpec("projects", "项目与成员", "建立项目空间，维护团队成员与职责。", "文件夹",
                List.of(
                        f("Epic1-US07", "项目创建", "创建项目并填写名称与简介。", "已实现", true),
                        f("Epic1-US08", "项目基本信息维护", "查看和修改项目基础资料。", "编辑项目", false),
                        f("Epic1-US09", "项目成员加入", "邀请开发人员加入项目。", "已实现", true),
                        f("Epic1-US10", "项目成员移除", "移除不再参与项目的成员。", "移除成员", false),
                        f("Epic1-US11", "项目成员状态管理", "维护成员正常、暂停等参与状态。", "管理状态", false),
                        f("Epic1-US16", "项目成员身份与职责查看", "负责人查看团队身份和职责分工。", "查看职责", false),
                        f("Epic1-US17", "个人项目身份与职责查看", "开发人员确认自己的工作范围。", "我的职责", false),
                        f("Epic1-US18", "其他成员身份与职责查看", "查看协作成员与责任边界。", "团队职责", false)
                )));
        pages.put("permissions", new PageSpec("permissions", "角色与权限", "系统管理员使用的角色与访问控制界面。", "锁",
                List.of(
                        f("Epic1-US12", "角色创建", "创建新的系统角色。", "新建角色", false),
                        f("Epic1-US13", "角色编辑", "修改角色名称与说明。", "编辑角色", false),
                        f("Epic1-US14", "用户角色分配", "为用户分配管理员、负责人或开发人员角色。", "分配角色", false),
                        f("Epic1-US15", "基于角色的权限控制", "配置角色可访问的页面和操作。", "权限矩阵", false)
                )));
        pages.put("backlog", new PageSpec("backlog", "Product Backlog", "集中管理用户故事与需求优先级。", "列表",
                List.of(
                        f("Epic2-US01", "Product Backlog 管理", "维护尚未进入 Sprint 的需求列表。", "新建条目", false),
                        f("Epic2-US02", "用户故事管理", "创建、编辑和维护用户故事。", "新建故事", false),
                        f("Epic2-US03", "用户故事优先级调整", "按项目价值调整故事顺序。", "调整优先级", false),
                        f("Epic2-US04", "Sprint 创建与维护", "设置迭代名称、目标和周期。", "创建 Sprint", false),
                        f("Epic2-US05", "用户故事加入 Sprint", "将选定故事纳入本轮迭代。", "加入 Sprint", false)
                )));
        pages.put("sprints", new PageSpec("sprints", "Sprint 管理", "查看迭代目标、时间范围与交付进度。", "日历",
                List.of(
                        f("Epic2-US04", "Sprint 创建与维护", "建立迭代并维护周期与目标。", "创建 Sprint", false),
                        f("Epic2-US05", "用户故事加入 Sprint", "从 Backlog 选择本轮范围。", "规划范围", false),
                        f("Epic3-US03", "Sprint 进度与完成情况", "检查迭代完成率和剩余工作。", "查看进度", false),
                        f("Epic3-US04", "计划进度与实际进度对比", "对比计划与实际推进情况。", "查看对比", false)
                )));
        pages.put("tasks", new PageSpec("tasks", "任务与协作", "以看板和任务详情组织团队执行。", "勾选",
                List.of(
                        f("Epic2-US06", "任务创建与编辑", "创建任务并维护任务详情。", "已实现", true),
                        f("Epic2-US07", "任务负责人分配", "将任务分配给项目成员。", "已实现", true),
                        f("Epic2-US08", "任务优先级与截止时间设置", "配置任务优先级和计划日期。", "设置计划", false),
                        f("Epic2-US09", "任务状态更新", "开发人员同步任务进展。", "已实现", true),
                        f("Epic2-US10", "任务看板", "按状态查看当前 Sprint 任务。", "打开看板", false),
                        f("Epic2-US11", "我的任务", "查看只分配给自己的任务。", "已实现", true),
                        f("Epic2-US12", "任务搜索、筛选与排序", "按多种条件定位任务。", "筛选任务", false),
                        f("Epic2-US13", "任务评论与协作记录", "围绕任务沟通并保留记录。", "发表评论", false),
                        f("Epic2-US14", "任务阻塞情况记录", "说明阻塞状态和原因。", "记录阻塞", false),
                        f("Epic2-US15", "任务依赖关系管理", "建立任务先后与依赖关系。", "添加依赖", false)
                )));
        pages.put("ai", new PageSpec("ai", "AI 协作", "在任务上下文中引入 AI 智能体辅助。", "闪光",
                List.of(
                        f("Epic2-US16", "AI 协作辅助", "辅助总结、整理信息与分析问题。", "询问 AI", false),
                        f("Epic2-US17", "AI 任务摘要", "根据任务内容自动生成进度摘要。", "生成摘要", false),
                        f("Epic2-US18", "人机协作", "组织开发人员与 AI 共同处理任务。", "添加智能体", false),
                        f("Epic2-US19", "AI 智能体配置", "管理智能体范围与基础权限。", "配置智能体", false)
                )));
        pages.put("analytics", new PageSpec("analytics", "数据与分析", "项目进度、负载和异常情况的统一视图。", "图表",
                List.of(
                        f("Epic3-US01", "项目总体进度与任务完成情况", "查看整体执行状态。", "查看详情", false),
                        f("Epic3-US02", "用户故事完成情况", "跟踪项目需求实现进度。", "故事分析", false),
                        f("Epic3-US03", "Sprint 进度与完成情况", "判断本轮能否按计划完成。", "Sprint 分析", false),
                        f("Epic3-US04", "计划进度与实际进度对比", "识别项目进度偏差。", "进度对比", false),
                        f("Epic3-US05", "延期与即将到期任务统计", "提前发现时间风险。", "到期任务", false),
                        f("Epic3-US06", "阻塞任务统计", "定位影响推进的阻塞问题。", "阻塞分析", false),
                        f("Epic3-US07", "成员任务负载分布", "发现任务分配不均。", "负载分析", false),
                        f("Epic3-US08", "任务状态变化趋势", "观察团队推进速度。", "趋势分析", false),
                        f("Epic3-US09", "项目数据仪表盘", "汇总核心项目指标。", "仪表盘", false),
                        f("Epic3-US10", "项目异常状态提示", "提示延期、阻塞和负载异常。", "异常中心", false),
                        f("Epic3-US11", "个人任务进度查看", "查看个人任务状态统计。", "个人进度", false),
                        f("Epic3-US12", "个人异常任务提醒", "关注个人即将到期和阻塞任务。", "我的提醒", false)
                )));
        return pages;
    }

    private static FeatureSpec f(String code, String title, String description, String action, boolean implemented) {
        return new FeatureSpec(code, title, description, action, implemented);
    }

    public record PageSpec(String slug, String title, String description, String icon, List<FeatureSpec> features) {}
    public record FeatureSpec(String code, String title, String description, String action, boolean implemented) {}
}
