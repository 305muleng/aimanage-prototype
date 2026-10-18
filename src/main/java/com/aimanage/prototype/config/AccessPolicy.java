package com.aimanage.prototype.config;

import com.aimanage.prototype.domain.Role;

/** Central route policy. New business routes must be explicitly granted. */
public final class AccessPolicy {
    private AccessPolicy() {}

    public static boolean publicRoute(String path) {
        return path.equals("/") || path.equals("/login") || path.equals("/register") || path.equals("/error");
    }

    public static boolean allows(Role role, String path) {
        if (role == null) return false;
        if (path.equals("/uml") || path.startsWith("/uml/")) return role == Role.PROJECT_MANAGER || role == Role.DEVELOPER;
        if (path.equals("/sprints") || path.equals("/iterations")) return role == Role.PROJECT_MANAGER || role == Role.DEVELOPER;
        if (path.equals("/backlog-sprint") || path.equals("/iterations/scope") || path.equals("/iterations/state")) return role == Role.PROJECT_MANAGER;
        if (path.equals("/backlog-map")) return role == Role.PROJECT_MANAGER;
        if (path.equals("/backlog")) return role == Role.PROJECT_MANAGER || role == Role.DEVELOPER;
        if (path.equals("/account") || path.equals("/logout") || path.equals("/access") || path.startsWith("/preview/")) return true;
        if (under(path, "/admin")) return role == Role.SYSTEM_ADMIN;
        if (under(path, "/manager")) return role == Role.PROJECT_MANAGER;
        if (under(path, "/my-tasks")) return role == Role.DEVELOPER;
        if (path.equals("/projects")) return role == Role.PROJECT_MANAGER || role == Role.DEVELOPER;
        return false;
    }

    private static boolean under(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
