package app.platform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import app.archfixture.illegal.IllegalApp;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Proves the architecture check is not vacuous: a deliberately broken module layout (test fixtures
 * under {@code app.archfixture.illegal}) must be rejected for each kind of violation.
 */
@Tag("architecture")
class ArchitectureCanFailTest {

    /** Captured once: verification results are memoised per module set, so only the first call throws. */
    private static String violations;

    @BeforeAll
    static void verifyBrokenFixture() {
        ApplicationModules fixture = ApplicationModules.of(IllegalApp.class, location -> true);
        try {
            fixture.verify();
        } catch (RuntimeException e) {
            violations = e.getMessage();
        }
    }

    @Test
    void verificationFailsOnBrokenLayout() {
        assertThat(violations).as("verify() must throw for the broken fixture").isNotNull();
    }

    @Test
    void rejectsDependencyOnAModuleThatIsNotAllowed() {
        assertThat(violations).contains("AlphaService").contains("beta");
    }

    @Test
    void rejectsAccessToAnotherModulesInternals() {
        assertThat(violations).contains("GammaService").contains("BetaInternal");
    }

    @Test
    void rejectsModuleCycles() {
        assertThat(violations).containsIgnoringCase("cycle").contains("delta").contains("epsilon");
    }
}
