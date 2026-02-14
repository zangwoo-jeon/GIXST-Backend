package com.AISA.AISA.global.config;

import com.AISA.AISA.global.jwt.JwtAuthenticationFilter;
import com.AISA.AISA.global.jwt.JwtTokenProvider;
import com.AISA.AISA.global.oauth.handler.OAuth2SuccessHandler;
import com.AISA.AISA.global.oauth.handler.CustomAuthenticationFailureHandler;
import com.AISA.AISA.global.oauth.service.CustomOAuth2UserService;
import com.AISA.AISA.global.oauth.repository.RedisOAuth2AuthorizationRequestRepository;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

        private final JwtTokenProvider jwtTokenProvider;
        private final CustomOAuth2UserService customOAuth2UserService;
        private final OAuth2SuccessHandler oAuth2SuccessHandler;
        private final CustomAuthenticationFailureHandler customAuthenticationFailureHandler;
        private final RedisOAuth2AuthorizationRequestRepository redisOAuth2AuthorizationRequestRepository;

        @Bean
        @Order(1)
        public SecurityFilterChain swaggerFilterChain(HttpSecurity http) throws Exception {
                RequestMatcher swaggerRequestMatcher = new OrRequestMatcher(
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/v3/api-docs/**"),
                                new AntPathRequestMatcher("/swagger-ui/index.html"));

                http
                                .securityMatcher(swaggerRequestMatcher)
                                .authorizeHttpRequests(authorize -> authorize
                                                .anyRequest().authenticated())
                                .httpBasic(withDefaults())
                                .formLogin(AbstractHttpConfigurer::disable)
                                .oauth2Login(AbstractHttpConfigurer::disable)
                                .csrf(AbstractHttpConfigurer::disable)
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .exceptionHandling(exception -> exception
                                                .authenticationEntryPoint((request, response, authException) -> {
                                                        response.addHeader("WWW-Authenticate",
                                                                        "Basic realm=\"Swagger\"");
                                                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                                                                        authException.getMessage());
                                                }));
                return http.build();
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                http
                                .securityMatcher("/api/**", "/oauth2/**", "/login/oauth2/code/**", "/actuator/**")
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .csrf(AbstractHttpConfigurer::disable)
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(authorize -> authorize
                                                .requestMatchers("/api/auth/**", "/oauth2/**", "/actuator/**")
                                                .permitAll()
                                                .requestMatchers(HttpMethod.GET, "/api/stocks/search",
                                                                "/api/stocks/volume-rank",
                                                                "/api/stocks/*/price", "/api/stocks/*/chart",
                                                                "/api/stocks/financial/**",
                                                                "/api/rank/financial/**", "/api/dividend/*/dividend",
                                                                "/api/dividend/*/detail",
                                                                "/api/dividend/rank", "/api/macro/exchange-rate/**",
                                                                "/api/macro/m2",
                                                                "/api/rank/investor",
                                                                "/api/macro/base-rate",
                                                                "/api/macro/cpi", "/api/macro/bond/**",
                                                                "/api/indices/{marketCode}/status",
                                                                "/api/indices/{marketCode}/chart",
                                                                "/api/indices/overseas/**",
                                                                "/api/indices/kospi-usd-ratio",
                                                                "/api/indices/kosdaq-usd-ratio",
                                                                "/api/indices/vkospi-usd-ratio",
                                                                "/api/analysis/valuation/*/static-report",
                                                                "/api/stocks/financial/investor-trend/daily/*",
                                                                "/api/stocks/investor/*/accumulated",
                                                                "/api/stocks/investor/market/**",
                                                                "/api/overseas/dividend/**",
                                                                "/api/overseas-stocks/search",
                                                                "/api/overseas-stocks/*/price",
                                                                "/api/overseas-stocks/*/chart",
                                                                "/api/overseas-stocks/information/financial-statement/*",
                                                                "/api/overseas-stocks/information/balance-sheet/*",
                                                                "/api/overseas-stocks/information/shareholder-return/*",
                                                                "/api/overseas-stocks/information/price-detail/*",
                                                                "/api/overseas-stocks/information/financial-ratio/*",
                                                                "/api/overseas-stocks/rank/**",
                                                                "/api/stocks/growth/ranking",
                                                                "/api/analysis/market/valuation",
                                                                "/api/analysis/overseas/static-analysis/*",
                                                                "/api/etf/*/detail",
                                                                "/api/etf/*/constituents",
                                                                "/api/etf/*/related")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .oauth2Login(oauth2 -> oauth2
                                                .authorizationEndpoint(authorization -> authorization
                                                                .baseUri("/oauth2/authorization")
                                                                .authorizationRequestRepository(
                                                                                redisOAuth2AuthorizationRequestRepository))
                                                .userInfoEndpoint(userInfo -> userInfo
                                                                .userService(customOAuth2UserService))
                                                .successHandler(oAuth2SuccessHandler)
                                                .failureHandler(customAuthenticationFailureHandler))
                                .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                                                UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        @Value("${swagger.auth.username:swagger}")
        private String swaggerUsername;

        @Value("${swagger.auth.password:swagger}")
        private String swaggerPassword;

        @Bean
        public UserDetailsService userDetailsService() {
                UserDetails user = User
                                .builder()
                                .username(swaggerUsername)
                                .password(passwordEncoder().encode(swaggerPassword))
                                .roles("SWAGGER")
                                .build();
                return new InMemoryUserDetailsManager(user);
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.addAllowedOrigin("http://localhost:3000");
                configuration.addAllowedOrigin("https://gixst.vercel.app");
                configuration.addAllowedMethod("*");
                configuration.addAllowedHeader("*");
                configuration.setAllowCredentials(true);

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}
