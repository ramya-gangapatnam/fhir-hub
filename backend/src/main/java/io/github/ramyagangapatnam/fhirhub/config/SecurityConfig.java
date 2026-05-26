package io.github.ramyagangapatnam.fhirhub.config;

import io.github.ramyagangapatnam.fhirhub.ingestion.AuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Wires the bearer-token AuthFilter into the Spring Security chain. The token is loaded from the
 * {@code FHIR_HUB_AUTH_TOKEN} environment variable; it is never logged or echoed.
 */
@Configuration
public class SecurityConfig {

  private final String authToken;

  public SecurityConfig(@Value("${fhir-hub.auth.token:${FHIR_HUB_AUTH_TOKEN:}}") String authToken) {
    if (authToken == null || authToken.isBlank()) {
      throw new IllegalStateException(
          "FHIR_HUB_AUTH_TOKEN must be set (env var or fhir-hub.auth.token property).");
    }
    this.authToken = authToken;
  }

  @Bean
  public AuthFilter authFilter() {
    return new AuthFilter(authToken);
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(authFilter(), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}
