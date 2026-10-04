package com.assignment.tickets.observability;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Adds the authenticated user to the MDC. Runs after Spring Security has authenticated the
 * request; the value stays in the MDC for the access-log line written by the outer filter.
 */
@Component
public class CallerMdcInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            RequestContext.put(RequestContext.USER_ID, auth.getName());
        }
        return true;
    }
}
