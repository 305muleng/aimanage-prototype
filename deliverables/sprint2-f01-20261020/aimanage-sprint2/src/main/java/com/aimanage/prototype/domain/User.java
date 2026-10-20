package com.aimanage.prototype.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Column(length = 100)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(length = 500)
    private String bio;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Role role;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "managed_role_id")
    private ManagedRole managedRole;

    protected User() {
    }

    public User(String username, String password, String displayName, Role role) {
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.role = role;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public void changePassword(String password) { this.password = password; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public ManagedRole getManagedRole() { return managedRole; }
    public String getManagedRoleName() { return managedRole == null ? role.getLabel() : managedRole.getName(); }
    public void assignRole(ManagedRole role) { this.managedRole=role; this.role=role.getAccessType(); }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getBio() { return bio; }
    public void updateProfile(String displayName, String email, String phone, String bio) {
        this.displayName = displayName;
        this.email = email;
        this.phone = phone;
        this.bio = bio;
    }
}
