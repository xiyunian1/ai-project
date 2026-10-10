package com.example.myai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor authInterceptor;
    private final String[] allowedOrigins;

    @Autowired
    public WebConfig(
            AuthInterceptor authInterceptor,
            @Value("${app.cors.allowed-origins:http://localhost:5173}") String originConfig,
            @Value("${app.cors.allow-null-origin:false}") boolean allowNullOrigin
    ) {
        this.authInterceptor = authInterceptor;
        this.allowedOrigins = Arrays.stream(originConfig.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .filter(origin -> allowNullOrigin || !"null".equalsIgnoreCase(origin))
                .toArray(String[]::new);
    }

    WebConfig(AuthInterceptor authInterceptor, String originConfig) {
        this(authInterceptor, originConfig, false);
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization", "Content-Disposition", "X-Processed-Preview-Base64", "X-Process-Mode")
                .maxAge(3600);
    }
}
