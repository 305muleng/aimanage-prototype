package com.aimanage.prototype.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 事务提交时发生版本冲突，不覆盖已有数据，返回原业务页面并提示刷新。 */
@ControllerAdvice(assignableTypes = {ManagerController.class, DeveloperController.class, TaskCollaborationController.class})
public class TaskConflictAdvice {
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public String conflict(HttpServletRequest request, RedirectAttributes redirect) {
        redirect.addFlashAttribute("error", "任务已被其他人修改，请刷新后重新操作");
        String path = request.getServletPath();
        if (path.startsWith("/manager/")) return "redirect:/manager";
        if (path.startsWith("/my-tasks/")) return "redirect:/my-tasks";
        String[] parts = path.split("/");
        return parts.length > 2 && parts[2].matches("[0-9]+") ? "redirect:/tasks/" + parts[2] : "redirect:/tasks";
    }
}
