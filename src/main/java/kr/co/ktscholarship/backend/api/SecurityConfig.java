package kr.co.ktscholarship.backend.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.*;

@Configuration
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
// 접근 제어
public class SecurityConfig {
  @Bean
  // 비밀번호 해시 설정
  PasswordEncoder passwordEncoder(){return new BCryptPasswordEncoder(12);}
  @Bean
  // 기본 계정 차단
  org.springframework.security.core.userdetails.UserDetailsService userDetailsService(){return username->{throw new org.springframework.security.core.userdetails.UsernameNotFoundException("BEARER_AUTHENTICATION_REQUIRED");};}

  @Bean
  // 보안 필터 설정
  SecurityFilterChain security(HttpSecurity http,AuthService auth,RequestBudget budget,Database d,@Value("${app.cors-origins:}")String origins,@Value("${app.metrics.token:}")String metricsToken) throws Exception {
    var cors=new CorsConfiguration();cors.setAllowedOrigins(origins.isBlank()?List.of():Arrays.asList(origins.split(",")));cors.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));cors.setAllowedHeaders(List.of("Authorization","Content-Type"));cors.setExposedHeaders(List.of("X-Request-Id"));cors.setAllowCredentials(false);
    var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/**",cors);
    http.csrf(c->c.disable()).cors(c->c.configurationSource(source)).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable()).logout(c->c.disable())
        .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .authorizeHttpRequests(c->c.requestMatchers("/actuator/health","/actuator/health/**","/v1/auth/login","/v1/auth/refresh","/v1/auth/mfa/enroll","/v1/auth/mfa/enroll/confirm","/v1/auth/mfa/verify").permitAll().requestMatchers("/actuator/prometheus").hasRole("OPS").requestMatchers("/actuator/**").denyAll().anyRequest().authenticated())
        .exceptionHandling(c->c.authenticationEntryPoint((r,s,e)->error(d,r,s,401,"UNAUTHORIZED")).accessDeniedHandler((r,s,e)->error(d,r,s,403,"FORBIDDEN")))
        .addFilterBefore(new OncePerRequestFilter(){
          // 인증 및 요청 예산
          protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            try {
              var context=AuditContext.current();String address=context==null?request.getRemoteAddr():context.address();
              String path=request.getRequestURI(),header=request.getHeader("Authorization");
              if(path.startsWith("/v1/auth/")&&!budget.allow("auth-ip",address,300)){error(d,request,response,429,"RATE_LIMITED");return;}
              AuthService.Actor actor=null;
              if(path.equals("/actuator/prometheus")&&!metricsToken.isBlank()&&header!=null&&MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),("Bearer "+metricsToken).getBytes(StandardCharsets.UTF_8)))actor=new AuthService.Actor(0,"OPS");
              else if(header!=null&&header.startsWith("Bearer ")&&header.length()<256)actor=auth.authenticate(header.substring(7));
              if(actor!=null){
                MDC.put("userId",Long.toString(actor.id()));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of(new SimpleGrantedAuthority("ROLE_"+actor.role()))));
                if(actor.mustChangePassword()&&!((path.equals("/users/me")||path.equals("/v1/users/me"))&&request.getMethod().equals("GET"))&&!(path.equals("/v1/users/me/password")&&request.getMethod().equals("PUT"))&&!(path.equals("/v1/auth/logout")&&request.getMethod().equals("POST"))){error(d,request,response,403,"PASSWORD_CHANGE_REQUIRED");return;}
                if(path.startsWith("/v1/")&&!budget.allow(request.getMethod().equals("GET")?"user-read":"user-write",Long.toString(actor.id()),request.getMethod().equals("GET")?300:60)){error(d,request,response,429,"RATE_LIMITED");return;}
              }
              chain.doFilter(request,response);
            }catch(ApiException e){error(d,request,response,e.status,e.code);}
          }
        },UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  // 보안 오류 응답
  private static void error(Database d,HttpServletRequest request,HttpServletResponse response,int status,String code) throws IOException {
    response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(d.encode(ApiAdvice.envelope(null,code,"인증 또는 접근 권한을 확인해 주세요.",request.getRequestURI())));
  }
}
