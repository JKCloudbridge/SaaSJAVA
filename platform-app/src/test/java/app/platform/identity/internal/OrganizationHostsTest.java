package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The server builds the address of an organization; the port of the request is kept, nothing else is taken from it. */
class OrganizationHostsTest {

    private final OrganizationHosts hosts = new OrganizationHosts("Platform.Example.Test");

    @Test
    void anOrganizationLivesUnderThePlatformDomainWithThePortOfTheRequest() {
        assertThat(hosts.of("org-a", "platform.example.test")).isEqualTo("org-a.platform.example.test");
        assertThat(hosts.of("org-a", "platform.example.test:3000")).isEqualTo("org-a.platform.example.test:3000");
        assertThat(hosts.of("org-a", "other-org.platform.example.test:8443"))
                .as("from another organization's host the label is replaced, not stacked")
                .isEqualTo("org-a.platform.example.test:8443");
    }

    @Test
    void textThatIsNotAPortIsNeverCopiedIntoTheAddress() {
        assertThat(hosts.of("org-a", "platform.example.test:evil.example")).isEqualTo("org-a.platform.example.test");
        assertThat(hosts.of("org-a", "platform.example.test:")).isEqualTo("org-a.platform.example.test");
        assertThat(hosts.of("org-a", "[::1]:3000")).isEqualTo("org-a.platform.example.test");
        assertThat(hosts.of("org-a", "")).isEqualTo("org-a.platform.example.test");
    }
}
