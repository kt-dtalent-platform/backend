package kr.co.ktscholarship.backend.config;

import jakarta.servlet.DispatcherType;
import kr.co.ktscholarship.backend.auth.jwt.JwtTokenProvider;
import kr.co.ktscholarship.backend.auth.security.ApiAccessDeniedHandler;
import kr.co.ktscholarship.backend.auth.security.ApiAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity // 역할이 섞인 경로는 메서드에 @PreAuthorize로 지정
public class SecurityConfig {

    private static final String MANAGER = "MANAGER";
    private static final String SCHOLAR = "SCHOLAR";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ApiAuthenticationEntryPoint authenticationEntryPoint,
            ApiAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http
                // 토큰 방식: 세션·쿠키 기반 기능은 끈다
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 위에서부터 처음 맞는 규칙 하나만 적용된다
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/actuator/health").permitAll()

                        .requestMatchers(
                                "/v1/programs/**",
                                "/v1/scholars/**",
                                "/v1/requests/**",
                                "/v1/request-templates/**",
                                "/v1/dashboard/manager"
                        ).hasRole(MANAGER)

                        .requestMatchers("/v1/me").authenticated()
                        .requestMatchers("/v1/me/**").hasRole(SCHOLAR)

                        // 그 밖의 경로는 로그인만 확인하고, 역할은 메서드의 @PreAuthorize로 검사
                        .anyRequest().authenticated()
                )

                // Authorization: Bearer 토큰을 꺼내 JwtDecoder로 검증하는 필터
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                );

        return http.build();
    }

    // 토큰의 role 클레임(MANAGER) → 권한 ROLE_MANAGER (hasRole("MANAGER")가 이 값을 확인)
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName(JwtTokenProvider.ROLE_CLAIM);
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
