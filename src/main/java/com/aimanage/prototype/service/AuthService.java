package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 负责演示账号的身份校验。
 *
 * <p>Sprint 1 使用统一登录入口，认证成功后的角色跳转由控制器处理。</p>
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final com.aimanage.prototype.repository.ManagedRoleRepository roles;

    public AuthService(UserRepository userRepository, com.aimanage.prototype.repository.ManagedRoleRepository roles) {
        this.userRepository = userRepository;
        this.roles = roles;
    }

    /**
     * 按账号和密码认证用户，并返回包含角色信息的用户实体。
     *
     * @throws BusinessException 账号不存在或密码不正确时抛出
     */
    @Transactional
    public User authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException("请输入账号和密码");
        }
        if (username.length() > 50 || password.length() > 64) throw new BusinessException("账号或密码错误，请检查后重试");
        User user = userRepository.findByUsername(username.trim())
                .filter(candidate -> PasswordCodec.matches(password, candidate.getPassword()))
                .orElseThrow(() -> new BusinessException("账号或密码错误，请检查后重试"));
        if (!user.getPassword().startsWith("pbkdf2$")) user.changePassword(PasswordCodec.encode(password));
        return user;
    }

    @Transactional
    public User register(String username, String displayName, String password, String confirmPassword) {
        username = username == null ? "" : username.trim();
        displayName = displayName == null ? "" : displayName.trim();
        if (!username.matches("[A-Za-z0-9_]{3,50}")) throw new BusinessException("账号需为 3–50 位字母、数字或下划线");
        if (displayName.isBlank() || displayName.length() > 50) throw new BusinessException("姓名需为 1–50 个字符");
        if (password == null || password.isBlank() || password.length() < 6 || password.length() > 64) {
            throw new BusinessException("密码需为 6–64 个字符，不能全为空格");
        }
        if (!password.equals(confirmPassword)) throw new BusinessException("两次输入的密码不一致");
        if (userRepository.existsByUsernameIgnoreCase(username)) throw new BusinessException("该账号已被注册，请更换账号");
        try {
            User user=new User(username, PasswordCodec.encode(password), displayName, Role.DEVELOPER);
            roles.findByBuiltInCode(Role.DEVELOPER.name()).ifPresent(user::assignRole);
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException("该账号已被注册，请更换账号");
        }
    }

    /** Only invoked by local demo initialization, never exposed through public registration. */
    @Transactional
    public void ensureDemoAdministrator() {
        if (!userRepository.existsByUsernameIgnoreCase("admin")) {
            userRepository.save(new User("admin", PasswordCodec.encode("123456"), "系统管理员", Role.SYSTEM_ADMIN));
        }
    }
}
