package com.pm.auth_service.config;
import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
@Component
public class CorrelationFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=request.getHeader("X-Correlation-Id");
        if(id==null || !id.matches("[A-Za-z0-9._:-]{1,64}")) { id=UUID.randomUUID().toString(); }
        String previous=MDC.get("correlationId");MDC.put("correlationId",id);response.setHeader("X-Correlation-Id",id);
        try { chain.doFilter(request,response); }
        finally { if(previous==null) { MDC.remove("correlationId"); } else { MDC.put("correlationId",previous); } }
    }
}
