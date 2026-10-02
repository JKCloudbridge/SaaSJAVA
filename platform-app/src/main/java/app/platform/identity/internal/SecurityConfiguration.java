package app.platform.identity.internal;

import app.platform.tenant.TenantContexts;
import app.platformapi.ApiPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The two security filter chains (ADR-0019). Spring Security's filter runs after the correlation and tenant filters, so
 * a refusal still carries the request ID, the tenant ID is already in the logs, and the tenant context is open when the
 * token is checked.
 *
 * <ol>
 *   <li><b>The authorization server's endpoints</b> (authorize, token, revoke, key set): the library's own filters,
 * with       the login cookie as the way the authorization endpoint learns who signed in, PKCE required, and the
 * redirect       address restricted to this host's callback.</li>
 *   <li><b>The API</b>: <em>everything denied unless allowed</em>. A short list of public paths (status, the OpenAPI
 *       document, the organization of the host, and the sign-in endpoints); every other request needs a valid token. A
 *       test walks every controller mapping and fails when a path is public without being on that list. State-changing
 *       requests that rely on the cookie need the CSRF header; a request with an {@code Authorization} header cannot be
 *       forged from another site and is exempt.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
class SecurityConfiguration {

    /** The only public API paths. Anything else under {@code /api/v1} needs a signed-in caller. */
    static final String[] PUBLIC_GET = {
        ApiPaths.PLATFORM_STATUS, ApiPaths.OPENAPI, ApiPaths.TENANT_CURRENT, ApiPaths.AUTH_CSRF, ApiPaths.AUTH_START,
        ApiPaths.AUTH_CALLBACK, "/error"
    };

    /**
     * Public POST paths. Sign-in checks the password itself; refresh and sign-out work from the refresh cookie, so they
     * must be reachable when the access token has already expired. Sign-up and password reset (Sprint 4) are for people
     * who have no session: the request steps are anonymous by nature, and the completing steps are authorized by the
     * one-time token from the e-mailed link. Sprint 5 adds three of the same kind: reading an invitation link,
     * accepting
     * it as a new person (both prove themselves with the link's token), and completing an organization switch on the
     * destination host (it proves itself with the one-time handoff). All of them are still protected by CSRF.
     */
    static final String[] PUBLIC_POST = {ApiPaths.AUTH_SIGN_IN, ApiPaths.AUTH_REFRESH, ApiPaths.AUTH_SIGN_OUT,
        ApiPaths.AUTH_SIGN_UP, ApiPaths.AUTH_SIGN_UP_COMPLETE, ApiPaths.AUTH_PASSWORD_FORGOT,
        ApiPaths.AUTH_PASSWORD_RESET, ApiPaths.AUTH_INVITATION_PREVIEW, ApiPaths.AUTH_INVITATION_ACCEPT_NEW,
        ApiPaths.AUTH_SWITCH_COMPLETE};

    /** Whether the request is for one of the public endpoints (by method and path). */
    static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String[] publicPaths = "GET".equals(request.getMethod()) ? PUBLIC_GET
                : "POST".equals(request.getMethod()) ? PUBLIC_POST : new String[0];
        for (String publicPath : publicPaths) {
            if (publicPath.equals(path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The probes and metrics. They are served on their own port, which only the platform's infrastructure can reach
     * (ADR-0012), and must answer without a sign-in: a load balancer and a metrics collector have none. On the API port
     * these paths are not served at all.
     */
    @Bean
    @Order(0)
    SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerChain(HttpSecurity http, RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations, AuthorizationServerSettings settings,
            OAuth2TokenGenerator<?> tokenGenerator, LoginSessions loginSessions, TenantContexts contexts,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors, IdentityProperties properties,
            @Value("${platform.tenancy.trust-forwarded-host:false}") boolean trustForwardedHost) throws Exception {
        ApiSecurityHandlers.EntryPoint entryPoint = new ApiSecurityHandlers.EntryPoint(errors);
        CallbackRedirectValidator redirects =
                new CallbackRedirectValidator(trustForwardedHost, properties.cookies().secure());
        http.oauth2AuthorizationServer(server -> {
            http.securityMatcher(server.getEndpointsMatcher());
            server.registeredClientRepository(clients)
                    .authorizationService(authorizations)
                    .authorizationServerSettings(settings)
                    .tokenGenerator(tokenGenerator)
                    .authorizationEndpoint(endpoint -> endpoint.authenticationProviders(providers ->
                            providers.forEach(provider -> {
                                if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider code) {
                                    code.setAuthenticationValidator(redirects);
                                }
                            })));
        });
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .securityContext(context -> context.securityContextRepository(
                        new LoginSessionSecurityContextRepository(loginSessions, contexts)))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new ApiSecurityHandlers.AuthorizeEntryPoint(entryPoint))
                        .accessDeniedHandler(new ApiSecurityHandlers.Denied(errors)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, AuthorizationStore store, TenantContexts contexts,
            MembershipGate gate, AuthAudit audit,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors, IdentityProperties properties)
            throws Exception {
        ApiSecurityHandlers.EntryPoint entryPoint = new ApiSecurityHandlers.EntryPoint(errors);
        ApiSecurityHandlers.Denied denied = new ApiSecurityHandlers.Denied(errors);

        CookieCsrfTokenRepository csrfTokens = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokens.setCookiePath("/");
        csrfTokens.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(properties.cookies().secure()));
        // The browser script reads the cookie and sends it back as a header; the token is not secret from that script.
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();

        RequestMatcher hasAuthorizationHeader = request -> {
            String header = request.getHeader("Authorization");
            return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
        };

        http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(csrfHandler)
                        .ignoringRequestMatchers(hasAuthorizationHeader))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resource -> resource
                        .bearerTokenResolver(new TokenAuthentication.Resolver())
                        .opaqueToken(token -> token
                                .introspector(new TokenAuthentication.Introspector(store, gate, audit)))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(denied))
                // The cookie becomes a header only after the forgery check, so a cookie alone never skips that check.
                .addFilterBefore(new TokenAuthentication.CookieTokenFilter(SecurityConfiguration::isPublic),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new UserContextFilter(contexts), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Makes sure the CSRF cookie is sent: the framework creates the token lazily and a page needs it to post. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
