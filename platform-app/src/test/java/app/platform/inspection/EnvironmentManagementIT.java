package app.platform.inspection;

/**
 * The Environment Management application (Functional track FA13B, ADR-0226): the battery every application of the kind
 * must pass, run for the shipped second application, whose objects, series, feature and access policies are in
 * {@code solutions/environment-management/}.
 */
class EnvironmentManagementIT extends ApplicationOfTheKindTests {

    @Override
    protected String application() {
        return "environment-management";
    }

    @Override
    protected String applicationFeature() {
        return "environment-management";
    }

    @Override
    protected String templateObject() {
        return "EnvironmentTemplate";
    }

    @Override
    protected String authorPolicy() {
        return "Environment Author";
    }

    @Override
    protected String approverPolicy() {
        return "Environment Approver";
    }
}
