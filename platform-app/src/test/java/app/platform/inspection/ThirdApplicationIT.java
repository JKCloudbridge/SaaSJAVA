package app.platform.inspection;

/**
 * The proof that an application of the kind is data only (Functional track FA13B, ADR-0226): a throwaway third
 * application made of nothing but files (a bundle, three objects, a numbering file, a migration that adds its plan
 * feature) runs the same battery as the shipped ones, with no change to any code. Its files are in
 * {@code src/test/third-application/}, put on the classpath of this test alone by its own failsafe execution in
 * {@code platform-app/pom.xml}, so no other test ever sees the application.
 *
 * <p>Run it alone with {@code ./mvnw -pl platform-app -am verify -Dit.test=ThirdApplicationIT -Dtest=NoSuchTest
 * -Dsurefire.failIfNoSpecifiedTests=false}; the default {@code verify} runs it too, in its own execution.
 */
class ThirdApplicationIT extends ApplicationOfTheKindTests {

    @Override
    protected String application() {
        return "throwaway-application";
    }

    @Override
    protected String applicationFeature() {
        return "throwaway-application";
    }

    @Override
    protected String templateObject() {
        return "ThrowawayTemplate";
    }

    @Override
    protected String authorPolicy() {
        return "Throwaway Author";
    }

    @Override
    protected String approverPolicy() {
        return "Throwaway Approver";
    }
}
