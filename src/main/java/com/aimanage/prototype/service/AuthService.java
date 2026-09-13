package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User authenticate(String username, String password) {
        return userRepository.findByUsername(username == null ? "" : username.trim())
                .filter(user -> user.getPassword().equals(password))
                .orElseThrow(() -> new BusinessException("账号或密码错误，请检查后重试"));
    }
}
