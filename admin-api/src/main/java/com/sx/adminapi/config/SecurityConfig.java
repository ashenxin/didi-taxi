package com.sx.adminapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sx.adminapi.auth.JwtService;
import com.sx.adminapi.client.PassengerAdminSysClient;
import com.sx.adminapi.security.AdminAccessDeniedHandler;
import com.sx.adminapi.security.AdminAuthenticationEntryPoint;
import com.sx.adminapi.security.AdminSecurityContextRedisCache;
import com.sx.adminapi.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ObjectMapper objectMapper) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/admin/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new AdminAuthenticationEntryPoint(objectMapper))
                        .accessDeniedHandler(new AdminAccessDeniedHandler(objectMapper)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            JwtService jwtService,
            PassengerAdminSysClient passengerAdminSysClient,
            ObjectMapper objectMapper,
            AdminSecurityContextRedisCache securityContextRedisCache) {
        return new JwtAuthenticationFilter(jwtService, passengerAdminSysClient, objectMapper, securityContextRedisCache);
    }
}
