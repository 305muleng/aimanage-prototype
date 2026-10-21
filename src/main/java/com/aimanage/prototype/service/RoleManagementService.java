package com.aimanage.prototype.service;
import com.aimanage.prototype.domain.*;
import com.aimanage.prototype.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.List;

@Service
@Transactional
public class RoleManagementService {
    private final ManagedRoleRepository roles;
    private final UserRepository users;
    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    public RoleManagementService(ManagedRoleRepository roles,UserRepository users,ProjectRepository projects,ProjectMemberRepository members) {
        this.roles=roles;this.users=users;this.projects=projects;this.members=members;
    }
    public void initializeBuiltIns() {
        for(Role type:Role.values()) {
            if(roles.findByBuiltInCode(type.name()).isEmpty()) {
                roles.save(new ManagedRole(type.getLabel(),"内置"+type.getLabel()+"权限",type,type.name()));
            }
        }
    }
    public void linkExistingUsers() {
        for(User user:users.findAll()) if(user.getManagedRole()==null) {
            user.assignRole(roles.findByBuiltInCode(user.getRole().name()).orElseThrow());
        }
    }
    @Transactional(readOnly=true)
    public List<ManagedRole> roles(Long adminId) { requireAdmin(adminId);return roles.findAllByOrderByIdAsc(); }
    @Transactional(readOnly=true)
    public List<UserRoleView> users(Long adminId) {
        requireAdmin(adminId);
        return users.findAll().stream().map(u->new UserRoleView(u.getId(),u.getUsername(),u.getDisplayName(),u.getRole(),
                u.getManagedRole()==null?null:u.getManagedRole().getId(),u.getManagedRoleName())).toList();
    }
    public ManagedRole create(Long adminId,String name,String description,Role accessType) {
        requireAdmin(adminId);
        name=name==null?"":name.trim();description=description==null?"":description.trim();
        if(name.isBlank()||name.length()>50) throw new BusinessException("角色名称需为 1–50 个字符");
        if(description.length()>500) throw new BusinessException("角色说明最多 500 个字符");
        if(accessType==null) throw new BusinessException("请选择权限类型");
        if(roles.existsByNameIgnoreCase(name)) throw new BusinessException("该角色名称已存在");
        try { return roles.saveAndFlush(new ManagedRole(name,description,accessType,null)); }
        catch(DataIntegrityViolationException ex) { throw new BusinessException("该角色名称已存在"); }
    }
    public void assign(Long adminId,Long userId,Long roleId) {
        // Serialize assignments so two admins cannot concurrently remove the last administrator.
        List<User> locked=users.lockAllUsers();
        User admin=locked.stream().filter(u->u.getId().equals(adminId)).findFirst().orElseThrow(()->new BusinessException("账号不存在"));
        if(admin.getRole()!=Role.SYSTEM_ADMIN) throw new BusinessException("只有系统管理员可以管理角色");
        User target=locked.stream().filter(u->u.getId().equals(userId)).findFirst().orElseThrow(()->new BusinessException("用户不存在"));
        ManagedRole next=roles.findById(roleId).orElseThrow(()->new BusinessException("角色不存在"));
        if(target.getRole()==Role.SYSTEM_ADMIN && next.getAccessType()!=Role.SYSTEM_ADMIN &&
                locked.stream().filter(u->u.getRole()==Role.SYSTEM_ADMIN).count()<=1) {
            throw new BusinessException("不能降级最后一个系统管理员，请先为其他用户分配管理员角色");
        }
        if(target.getRole()!=next.getAccessType()) {
            if(target.getRole()==Role.PROJECT_MANAGER && !projects.findByOwnerIdOrderByCreatedAtDesc(target.getId()).isEmpty())
                throw new BusinessException("该用户仍负责项目，不能更改其负责人权限类型");
            if(target.getRole()==Role.DEVELOPER && !members.findByUserId(target.getId()).isEmpty())
                throw new BusinessException("该用户仍是项目成员，请先转交任务并移出项目，再更改权限类型");
        }
        target.assignRole(next);
    }
    private void requireAdmin(Long id) {
        if(id==null || users.findById(id).map(u->u.getRole()!=Role.SYSTEM_ADMIN).orElse(true))
            throw new BusinessException("只有系统管理员可以管理角色");
    }
    public record UserRoleView(Long id,String username,String displayName,Role accessType,Long roleId,String roleName) {}
}
