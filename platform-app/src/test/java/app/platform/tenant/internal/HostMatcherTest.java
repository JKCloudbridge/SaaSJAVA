package app.platform.tenant.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.tenant.internal.HostMatcher.ForeignHost;
import app.platform.tenant.internal.HostMatcher.InvalidTenantHost;
import app.platform.tenant.internal.HostMatcher.PlatformHost;
import app.platform.tenant.internal.HostMatcher.TenantHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostMatcherTest {

    private final HostMatcher matcher = new HostMatcher("platform.example.test");

    @Test
    void anOrganizationHostCarriesItsSlug() {
        assertThat(matcher.match("tenant-a.platform.example.test"))
                .isInstanceOfSatisfying(TenantHost.class,
                        host -> assertThat(host.slug().value()).isEqualTo("tenant-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TENANT-A.platform.example.test", "tenant-a.platform.example.test:8443",
        "tenant-a.platform.example.test.", "  tenant-a.platform.example.test  ", "Tenant-A.Platform.Example.Test:80"})
    void caseAPortAndATrailingDotDoNotChangeTheOrganization(String raw) {
        assertThat(matcher.match(raw)).isInstanceOfSatisfying(TenantHost.class,
                host -> assertThat(host.slug().value()).isEqualTo("tenant-a"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform.example.test", "platform.example.test:8080", "www.platform.example.test",
        "api.platform.example.test", "admin.platform.example.test"})
    void theBaseDomainAndReservedNamesAreThePlatformsOwnHosts(String raw) {
        assertThat(matcher.match(raw)).isInstanceOf(PlatformHost.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.b.platform.example.test", "ab.platform.example.test", "-a-.platform.example.test",
        "tenant--a.platform.example.test", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx.platform.example.test"})
    void anythingElseUnderTheBaseDomainCanNeverBeAnOrganization(String raw) {
        assertThat(matcher.match(raw)).isInstanceOf(InvalidTenantHost.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.0.0.1", "127.0.0.1:8080", "[::1]", "[::1]:8080", "example.test",
        "tenant-a.example.test", "tenant-a.platform.example.test.evil.test", "evilplatform.example.test",
        "tenant-a.xplatform.example.test", "", " ", "tenant a.platform.example.test",
        "tenant-a.platform.example.test/x",
        "tenant-a@platform.example.test", ".platform.example.test", "tenant-a..platform.example.test"})
    void hostsOutsideTheBaseDomainAndMalformedHostsResolveNoOrganization(String raw) {
        assertThat(matcher.match(raw)).isInstanceOf(ForeignHost.class);
    }

    @Test
    void aMissingHostResolvesNoOrganization() {
        assertThat(matcher.match(null)).isInstanceOf(ForeignHost.class);
    }

    @Test
    void aSuffixLookalikeDoesNotMatch() {
        // "evilplatform.example.test" ends with the base domain's letters but not with ".platform.example.test".
        assertThat(matcher.match("evilplatform.example.test")).isInstanceOf(ForeignHost.class);
    }

    @Test
    void localDevelopmentUsesLocalhostAsTheBaseDomain() {
        HostMatcher local = new HostMatcher("localhost");

        assertThat(local.match("tenant-a.localhost:3000")).isInstanceOfSatisfying(TenantHost.class,
                host -> assertThat(host.slug().value()).isEqualTo("tenant-a"));
        assertThat(local.match("localhost:3000")).isInstanceOf(PlatformHost.class);
    }
}
