package app.torrentmanager.settings;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

/** No login on the LAN; reject browser commands originating on unrelated sites. */
@Component
public class AdminAccess implements WebMvcConfigurer, HandlerInterceptor {
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/admin/**");
    }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        boolean mutating = !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
        boolean blocked = "cross-site".equals(request.getHeader("Sec-Fetch-Site"));
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                URI uri = URI.create(origin);
                int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
                blocked |= !request.getScheme().equalsIgnoreCase(uri.getScheme())
                        || !request.getServerName().equalsIgnoreCase(uri.getHost())
                        || request.getServerPort() != port;
            } catch (IllegalArgumentException exception) { blocked = true; }
        }
        // A custom header forces cross-origin scripts through a CORS preflight;
        // cross-origin access is not enabled. Ordinary HTML forms cannot set it.
        blocked |= mutating && !"1".equals(request.getHeader("X-Torrent-Manager"));
        if (blocked) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"Откройте панель менеджера для выполнения команды\"}");
            return false;
        }
        return true;
    }
}
