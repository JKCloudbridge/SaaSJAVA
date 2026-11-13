package app.platform.inspection;

/**
 * The Management Core application (MC1, ADR-0257): the battery every application of the kind must pass, run for the
 * third shipped application, whose objects, series, feature and access policies are in
 * {@code solutions/management-core/}. It checks the gate (feature on, off, plan, override, cross-tenant, forged
 * host, platform administrator, not signed in) and that a form made here is never listed in another application, and
 * the reverse.
 */
class ManagementCoreIT extends ApplicationOfTheKindTests {

    @Override
    protected String application() {
        return "management-core";
    }

    @Override
    protected String applicationFeature() {
        return "management-core";
    }

    @Override
    protected String templateObject() {
        return "ManagementTemplate";
    }

    @Override
    protected String authorPolicy() {
        return "Management Author";
    }

    @Override
    protected boolean sharesCountersWithTheQualityManagementSystem() {
        return true;
    }

    @Override
    protected String approverPolicy() {
        return "Management Approver";
    }
}
