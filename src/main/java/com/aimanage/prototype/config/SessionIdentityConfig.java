package com.aimanage.prototype.config;
import com.aimanage.prototype.controller.SessionKeys;
import com.aimanage.prototype.repository.UserRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.*;
import com.aimanage.prototype.domain.Role;
import org.springframework.web.method.HandlerMethod;

@Configuration
public class SessionIdentityConfig implements WebMvcConfigurer {
    private final UserRepository users;
    public SessionIdentityConfig(UserRepository users) { this.users=users; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) throws java.io.IOException {
                HttpSession session=request.getSession(false);
                Role effectiveRole=null;
                if(session!=null && session.getAttribute(SessionKeys.USER_ID) instanceof Long id) {
                    var current=users.findById(id);
                    if(current.isEmpty()) session.invalidate();
                    else {
                        var user=current.get();
                        effectiveRole=user.getRole();
                        session.setAttribute(SessionKeys.ROLE,user.getRole());
                        session.setAttribute(SessionKeys.DISPLAY_NAME,user.getDisplayName());
                        session.setAttribute("managedRoleName",user.getManagedRoleName());
                    }
                }
                // Controller arguments are bound only after this check, including on POST requests.
                String path=org.springframework.web.util.UrlPathHelper.defaultInstance.getPathWithinApplication(request);
                if(handler instanceof HandlerMethod && !AccessPolicy.publicRoute(path) && !AccessPolicy.allows(effectiveRole,path)) {
                    response.sendRedirect(request.getContextPath()+"/login");
                    return false;
                }
                if(session!=null && effectiveRole==null) {
                    try { session.removeAttribute(SessionKeys.ROLE); session.removeAttribute(SessionKeys.USER_ID); }
                    catch(IllegalStateException ignored) { /* Already invalidated for a deleted user. */ }
                }
                return true;
            }
        }).excludePathPatterns("/css/**","/js/**","/error");
    }
}
