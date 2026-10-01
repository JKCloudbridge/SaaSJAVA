package app.platform.tenant.internal;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of tenant resolution by host name (ADR-0017).
 *
 * @param baseDomain the platform domain: an organization lives at {@code <slug>.<baseDomain>}; the domain itself and
 *        reserved names under it are platform hosts without a tenant. Required in every environment (variable
 *        {@code PLATFORM_BASE_DOMAIN}); there is deliberately no default, so a deployment cannot silently run with
 *        the wrong one.
 * @param trustForwardedHost whether the host name is taken from the {@code X-Forwarded-Host} header instead of the
 *        {@code Host} header. Switch it on only where a trusted proxy in front of the API overwrites that header on
 *        every request (the local development proxy, a deployment's ingress). With it on and the API reachable
 *        directly, a caller could name any tenant host, which is why the default is off.
 */
@ConfigurationProperties("platform.tenancy")
record TenancyProperties(String baseDomain, @DefaultValue("false") boolean trustForwardedHost) {

    private static final Pattern DOMAIN = Pattern.compile(
            "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*");

    TenancyProperties {
        if (baseDomain == null || baseDomain.isBlank()) {
            throw new IllegalArgumentException("platform.tenancy.base-domain (PLATFORM_BASE_DOMAIN) is required");
        }
        baseDomain = baseDomain.strip().toLowerCase(Locale.ROOT);
        if (!DOMAIN.matcher(baseDomain).matches()) {
            throw new IllegalArgumentException(
                    "platform.tenancy.base-domain must be a plain domain name such as example.test");
        }
    }
}
