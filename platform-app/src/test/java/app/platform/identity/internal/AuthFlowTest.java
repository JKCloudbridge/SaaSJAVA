package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The page the browser lands on after sign-in must be a path on this site, never another address. */
class AuthFlowTest {

    @Test
    void aPlainPathOnThisSiteIsKept() {
        assertThat(AuthFlow.safeLandingPath("/")).isEqualTo("/");
        assertThat(AuthFlow.safeLandingPath("/reports/2026?tab=open")).isEqualTo("/reports/2026?tab=open");
    }

    @Test
    void anythingThatCouldLeaveTheSiteBecomesTheHomePage() {
        for (String candidate : new String[] {
            null, "", "https://evil.example.test", "//evil.example.test", "///evil.example.test",
            "\\\\evil.example.test", "/\\evil.example.test", "evil.example.test/path", "javascript:alert(1)",
            "/ok\r\nSet-Cookie: x=y", "/" + "a".repeat(300)}) {
            assertThat(AuthFlow.safeLandingPath(candidate)).as(String.valueOf(candidate)).isEqualTo("/");
        }
    }
}
