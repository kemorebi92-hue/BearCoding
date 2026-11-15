package com.example.jingjingai2.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 打包后的 Vue 静态文件。API 路径仍走控制器；没有对应文件时回到 index.html，供前端历史路由使用。
 */
@Configuration
public class SpaResourceConfig implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource found = super.getResource(resourcePath, location);
                        if (found != null) return found;
                        if (resourcePath.startsWith("ai/") || resourcePath.startsWith("api/")
                                || resourcePath.startsWith("user/")) {
                            return null;
                        }
                        Resource index = new ClassPathResource("/static/index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
