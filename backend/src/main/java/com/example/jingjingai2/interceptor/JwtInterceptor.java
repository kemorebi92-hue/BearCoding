package com.example.jingjingai2.interceptor;

import com.example.jingjingai2.identity.CurrentUserProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 本机单用户身份注入。不读 Authorization，客户端不能改写 userId。
 */
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired private CurrentUserProvider currentUserProvider;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return true;
        }
        Long userId = currentUserProvider.getCurrentUserId();
        if (userId == null) {
            response.setStatus(500);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"本机身份未配置\"}");
            return false;
        }
        request.setAttribute("userId", userId);
        return true;
    }
}
