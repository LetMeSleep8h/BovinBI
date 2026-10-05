package com.eighthours.bovinbi.config;

import com.eighthours.bovinbi.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /**
     * SPA history 路由兜底:前端用 createWebHistory,直接访问/刷新 /chat、/text2sql
     * 等路径时请求会落到后端 —— 无对应静态资源则回 index.html 交前端路由接管。
     * API/MCP/网关/文档路径不兜底,保持原 404/鉴权语义。
     */
    @Override
    public void addResourceHandlers(org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new org.springframework.web.servlet.resource.PathResourceResolver() {
                    @Override
                    protected org.springframework.core.io.Resource getResource(String resourcePath,
                            org.springframework.core.io.Resource location) throws java.io.IOException {
                        org.springframework.core.io.Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        boolean backendPath = resourcePath.startsWith("api/") || resourcePath.startsWith("mcp")
                                || resourcePath.startsWith("gateway/") || resourcePath.startsWith("swagger")
                                || resourcePath.startsWith("v3/") || resourcePath.startsWith("assets/")
                                || resourcePath.startsWith("actuator");
                        return backendPath ? null
                                : new org.springframework.core.io.ClassPathResource("/static/index.html");
                    }
                });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register", "/api/health");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
