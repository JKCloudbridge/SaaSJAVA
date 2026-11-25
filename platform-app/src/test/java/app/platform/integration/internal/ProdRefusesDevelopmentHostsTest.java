package app.platform.integration.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The development hosts weaken the address rules of every outbound call (plain http, private addresses) and exist for
 * a developer machine and the tests. A deployment must never run with one (Sprint 33, ADR-0291): the prod profile
 * refuses to start, and a developer machine and the tests are not affected.
 */
class ProdRefusesDevelopmentHostsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntegrationModuleConfiguration.class);

    @Test
    void theProdProfileDoesNotStartWithADevelopmentHost() {
        runner.withPropertyValues("spring.profiles.active=prod",
                "platform.integration.egress.development-hosts=127.0.0.1").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "platform.integration.egress.development-hosts must be empty in the prod profile");
                });
    }

    @Test
    void theProdProfileStartsWithTheListEmpty() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void anyOtherProfileMayHaveDevelopmentHosts() {
        runner.withPropertyValues("spring.profiles.active=local",
                "platform.integration.egress.development-hosts=127.0.0.1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theProdPolicyHasNoDevelopmentHostAtAll() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            EndpointRules.Policy policy = context.getBean(EndpointRules.Policy.class);
            assertThat(policy.developmentHosts()).isEmpty();
        });
    }
}
