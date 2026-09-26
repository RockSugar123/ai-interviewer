package com.aiinterviewer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * index.html 禁止缓存：前端重新构建后入口页面必须拉新（assets 文件名带 hash 可长期缓存），
 * 否则浏览器按 Last-Modified 启发式缓存，部署新前端后用户仍会看到旧界面。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IndexCacheFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if ("/".equals(uri) || "/index.html".equals(uri)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
        chain.doFilter(request, response);
    }
}
