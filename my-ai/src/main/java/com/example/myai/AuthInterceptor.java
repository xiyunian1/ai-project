package com.example.myai;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    public static final String ATTR_USERNAME = "authUsername";
    public static final String ATTR_DISPLAY_NAME = "authDisplayName";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/auth/login",
            "/api/auth/register"
    );

    private final JwtService jwtService;

    public AuthInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String requestUri = request.getRequestURI();
        if (!requestUri.startsWith("/api/") || PUBLIC_PATHS.contains(requestUri)) {
            return true;
        }

        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(response, "缺少认证信息");
        }

        try {
            JwtService.JwtPrincipal principal = jwtService.parseToken(token);
            request.setAttribute(ATTR_USERNAME, principal.username());
            request.setAttribute(ATTR_DISPLAY_NAME, principal.displayName());
            return true;
        } catch (JwtException | IllegalArgumentException ex) {
            return unauthorized(response, "登录已过期，请重新登录");
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    private boolean unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"" + escapeJson(message) + "\"}");
        return false;
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
