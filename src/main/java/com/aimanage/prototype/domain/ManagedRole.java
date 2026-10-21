package com.aimanage.prototype.domain;
import jakarta.persistence.*;

/** Database role whose access type reuses the established application authorization rules. */
@Entity
@Table(name = "managed_role")
public class ManagedRole {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 50)
    private String name;
    @Column(nullable = false, length = 500)
    private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private Role accessType;
    @Column(unique = true, length = 30)
    private String builtInCode;
    protected ManagedRole() {}
    public ManagedRole(String name, String description, Role accessType, String builtInCode) {
        this.name=name; this.description=description; this.accessType=accessType; this.builtInCode=builtInCode;
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Role getAccessType() { return accessType; }
    public String getBuiltInCode() { return builtInCode; }
}
