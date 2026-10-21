package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.Role;
import com.aimanage.prototype.domain.User;
import com.aimanage.prototype.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileService {
    private final UserRepository users;
    public ProfileService(UserRepository users) { this.users = users; }

    @Transactional(readOnly = true)
    public Profile get(Long currentUserId) { return view(requireUser(currentUserId)); }

    @Transactional
    public Profile update(Long currentUserId, String displayName, String email, String phone, String bio) {
        User user = requireUser(currentUserId);
        displayName = clean(displayName);
        email = clean(email);
        phone = clean(phone);
        bio = clean(bio);
        if (displayName.isBlank() || displayName.length() > 50) throw new BusinessException("姓名需为 1–50 个字符");
        if (email.length() > 100 || (!email.isEmpty() && !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            throw new BusinessException("请输入有效邮箱，最多 100 个字符");
        }
        if (phone.length() > 30 || (!phone.isEmpty() && !phone.matches("[0-9+()\\- ]{3,30}"))) {
            throw new BusinessException("联系电话需为 3–30 位数字、空格或 + ( ) -");
        }
        if (bio.length() > 500) throw new BusinessException("个人简介最多 500 个字符");
        user.updateProfile(displayName, email, phone, bio);
        return view(user);
    }

    private User requireUser(Long id) {
        if (id == null) throw new BusinessException("请先登录");
        return users.findById(id).orElseThrow(() -> new BusinessException("账号不存在，请重新登录"));
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static Profile view(User user) {
        return new Profile(user.getUsername(), user.getRole(), user.getDisplayName(), clean(user.getEmail()), clean(user.getPhone()), clean(user.getBio()));
    }
    // Only non-sensitive fields are exposed to the template.
    public record Profile(String username, Role role, String displayName, String email, String phone, String bio) {}
}
