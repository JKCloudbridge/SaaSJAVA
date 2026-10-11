package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;

import app.platform.identity.Users;
import app.platform.licensing.Plans;
import app.platform.licensing.Subscriptions;
import app.platform.metadata.CompiledFormula;
import app.platform.metadata.CompiledRule;
import app.platform.metadata.FieldType;
import app.platform.metadata.FormulaOutcome;
import app.platform.metadata.Metadata;
import app.platform.metadata.MetadataResolver;
import app.platform.metadata.RuleOutcome;
import app.platform.sharedkernel.events.EventEnvelope;
import app.platform.tenant.TenantContext;
import app.platform.tenant.TenantContexts;
import app.platform.testsupport.PlatformIntegrationTest;
import app.platform.testsupport.TestBrowser;
import app.platform.testsupport.TestHttp.Response;
import app.platform.testsupport.TestOrganizations;
import app.platform.testsupport.TestOrganizations.Organization;
import app.platform.testsupport.TestPlatform;
import app.platform.testsupport.tenancy.TenantFixtures;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The runtime read contract against a real database (Sprint 13, ADR-0080): the compiled rules and formulas of an object
 * are handed out ready to evaluate, in the order the formulas must be calculated, and they are made once per metadata
 * version: repeated questions are served from the snapshot, and a change of definitions makes the next question build
 * again. Metadata reads hit the cache: a run of reads costs one build.
 */
@PlatformIntegrationTest
class MetadataRuntimeIT {

    private static final String OBJECTS = "/api/v1/metadata/objects";
    private static final Instant NOW = Instant.parse("2030-01-31T09:30:00Z");

    @LocalServerPort
    private int port;

    @Autowired
    private Users users;

    @Autowired
    private Plans plans;

    @Autowired
    private Subscriptions subscriptions;

    @Autowired
    private MetadataResolver resolver;

    @Autowired
    private MetadataRuntime runtime;

    @Autowired
    private Metadata metadata;

    @Autowired
    private MetadataCache cache;

    @Autowired
    private TenantContexts contexts;

    @Autowired
    private MetadataWarmer warmer;

    private TestBrowser adminWithOrder(Organization organization) {
        TestBrowser admin = TestOrganizations.signedIn(port, organization.host(), organization.admin().person());
        ok(admin.postJson(OBJECTS, "{\"name\":\"Order\",\"label\":\"Order\",\"pluralLabel\":\"Orders\"}"));
        for (String field : List.of("quantity:NUMBER", "price:CURRENCY", "closeDate:DATE")) {
            String[] parts = field.split(":");
            ok(admin.postJson(OBJECTS + "/Order__c/fields", "{\"name\":\"" + parts[0] + "\",\"label\":\"" + parts[0]
                    + "\",\"type\":\"" + parts[1] + "\"}"));
        }
        return admin;
    }

    private Organization organization() {
        Organization organization = TestOrganizations.create(users);
        TestPlatform.subscribe(plans, subscriptions, organization.id(), 5, 3);
        return organization;
    }

    private static void ok(Response response) {
        assertThat(response.status()).as(response.body()).isBetween(200, 204);
    }

    private static void formula(TestBrowser admin, String name, String expression, String type) {
        ok(admin.postJson(OBJECTS + "/Order__c/fields", "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"type\":\"FORMULA\",\"settings\":{\"expression\":\"" + expression + "\",\"resultType\":\""
                + type + "\"}}"));
    }

    private static void rule(TestBrowser admin, String name, String expression, boolean active) {
        ok(admin.postJson(OBJECTS + "/Order__c/validation-rules", "{\"name\":\"" + name + "\",\"label\":\"" + name
                + "\",\"expression\":\"" + expression + "\",\"errorMessage\":\"Message\",\"active\":" + active + "}"));
    }

    private <T> T asOrganization(Organization organization, java.util.function.Supplier<T> work) {
        return contexts.call(TenantContext.of(organization.id()), work::get);
    }

    @Test
    void theRulesAreHandedOutReadyToJudgeAndAnInactiveRuleIsLeftOut() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        rule(admin, "NoNegative", "quantity__c < 0", true);
        rule(admin, "NotYet", "TRUE", false);
        rule(admin, "Overdue", "closeDate__c < TODAY()", true);

        List<CompiledRule> rules = asOrganization(organization, () -> resolver.validationRules("Order__c"));

        assertThat(rules).extracting(CompiledRule::apiName).containsExactly("NoNegative__c", "Overdue__c");
        CompiledRule negative = rules.get(0);
        assertThat(negative.errorMessage()).isEqualTo("Message");
        assertThat(negative.fields()).containsExactly("quantity__c");
        assertThat(negative.evaluate(Map.of("quantity__c", BigDecimal.valueOf(-1)), NOW))
                .isEqualTo(RuleOutcome.rejected());
        assertThat(negative.evaluate(Map.of("quantity__c", BigDecimal.ONE), NOW)).isEqualTo(RuleOutcome.passes());
        assertThat(negative.evaluate(Map.of(), NOW)).as("no value does not reject").isEqualTo(RuleOutcome.passes());
        assertThat(rules.get(1).evaluate(Map.of("closeDate__c", LocalDate.parse("2030-01-30")), NOW).rejects())
                .as("the moment of the save is an argument").isTrue();
        assertThat(rules.get(1).evaluate(Map.of("closeDate__c", LocalDate.parse("2030-02-01")), NOW).rejects())
                .isFalse();
    }

    @Test
    void aRuleThatCannotBeEvaluatedIsReportedAndNeverPassedInSilence() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        rule(admin, "Divides", "10 / quantity__c > 1", true);

        CompiledRule divides = asOrganization(organization, () -> resolver.validationRules("Order__c")).get(0);
        RuleOutcome failed = divides.evaluate(Map.of("quantity__c", BigDecimal.ZERO), NOW);
        RuleOutcome wrongKind = divides.evaluate(Map.of("quantity__c", "ten"), NOW);

        assertThat(failed.evaluated()).isFalse();
        assertThat(failed.problem()).contains("divides by zero");
        assertThat(wrongKind.evaluated()).isFalse();
        assertThat(wrongKind.problem()).contains("quantity__c");
    }

    @Test
    void theFormulasAreHandedOutInTheOrderTheyMustBeCalculated() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        formula(admin, "total", "quantity__c * price__c", "CURRENCY");
        formula(admin, "doubled", "total__c * 2", "CURRENCY");
        formula(admin, "label", "CONCAT('Total: ', TEXT(doubled__c))", "TEXT");

        List<CompiledFormula> formulas = asOrganization(organization, () -> resolver.formulas("Order__c"));

        assertThat(formulas).extracting(CompiledFormula::fieldApiName).containsExactly("total__c", "doubled__c",
                "label__c");
        assertThat(formulas).extracting(CompiledFormula::depth).containsExactly(1, 2, 3);
        assertThat(formulas).extracting(CompiledFormula::resultType).containsExactly(FieldType.CURRENCY,
                FieldType.CURRENCY, FieldType.TEXT);
        Map<String, Object> values = new HashMap<>();
        values.put("quantity__c", BigDecimal.valueOf(3));
        values.put("price__c", new BigDecimal("2.50"));
        for (CompiledFormula formula : formulas) {
            FormulaOutcome outcome = formula.calculate(values);
            assertThat(outcome.calculated()).as(formula.fieldApiName()).isTrue();
            values.put(formula.fieldApiName(), outcome.value());
        }
        assertThat(values.get("total__c")).isEqualTo(new BigDecimal("7.5"));
        assertThat(values.get("doubled__c")).isEqualTo(BigDecimal.valueOf(15));
        assertThat(values.get("label__c")).isEqualTo("Total: 15");
    }

    @Test
    void aFormulaThatCannotBeCalculatedIsReportedWithThePlace() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        formula(admin, "ratio", "100 / quantity__c", "NUMBER");

        CompiledFormula ratio = asOrganization(organization, () -> resolver.formulas("Order__c")).get(0);
        FormulaOutcome outcome = ratio.calculate(Map.of("quantity__c", BigDecimal.ZERO));

        assertThat(outcome.calculated()).isFalse();
        assertThat(outcome.problem()).startsWith("Line 1, column 5: ");
        assertThat(ratio.calculate(Map.of("quantity__c", BigDecimal.valueOf(4))).value())
                .isEqualTo(BigDecimal.valueOf(25));
    }

    @Test
    void anObjectWithNothingToCompileAndAnUnknownObjectHaveEmptyLists() {
        Organization organization = organization();
        adminWithOrder(organization);

        assertThat(asOrganization(organization, () -> resolver.validationRules("Order__c"))).isEmpty();
        assertThat(asOrganization(organization, () -> resolver.formulas("Order__c"))).isEmpty();
        assertThat(asOrganization(organization, () -> resolver.validationRules("Ghost__c"))).isEmpty();
        assertThat(asOrganization(organization, () -> resolver.formulas("Account"))).isEmpty();
    }

    @Test
    void theArtefactsAreMadeOncePerVersionAndAChangeMakesTheNextQuestionBuildAgain() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        rule(admin, "NoNegative", "quantity__c < 0", true);
        asOrganization(organization, () -> resolver.validationRules("Order__c"));
        long built = runtime.builtCount();
        long served = runtime.servedCount();

        for (int i = 0; i < 25; i++) {
            asOrganization(organization, () -> resolver.validationRules("Order__c"));
        }

        assertThat(runtime.builtCount()).as("nothing was built again").isEqualTo(built);
        assertThat(runtime.servedCount() - served).isEqualTo(25);

        rule(admin, "Another", "quantity__c > 1000", true);

        assertThat(asOrganization(organization, () -> resolver.validationRules("Order__c")))
                .extracting(CompiledRule::apiName).containsExactly("NoNegative__c", "Another__c");
        assertThat(runtime.builtCount()).as("the change made the next question build").isGreaterThan(built);
    }

    @Test
    void aRunOfMetadataReadsHitsTheCacheAndBuildsOnce() {
        Organization organization = organization();
        adminWithOrder(organization);
        asOrganization(organization, () -> metadata.objects());
        long hits = cache.hitCount();
        long misses = cache.missCount();

        for (int i = 0; i < 40; i++) {
            asOrganization(organization, () -> metadata.object("Order__c").orElseThrow());
        }

        assertThat(cache.missCount() - misses).as("no build during a run of reads").isZero();
        assertThat(cache.hitCount() - hits).as("every read was served from the cache").isEqualTo(40);
    }

    // ---- the publish event and the warm-up (ADR-0080) ----

    private static long events(Organization organization) throws SQLException {
        return TenantFixtures.asTenant(organization.id(), connection -> TenantFixtures.count(connection,
                "select count(*) from outbox_event where event_type = 'metadata.published'"));
    }

    private static long releases(TestBrowser admin) {
        return JsonPath.<List<Object>>read(admin.get("/api/v1/metadata/releases").body(), "$.data").size();
    }

    private EventEnvelope published(Organization organization) {
        return new EventEnvelope(UUID.randomUUID(), organization.id(), null, null, MetadataWarmer.EVENT_TYPE,
                "{\"release\":1,\"version\":1}", Instant.now(), 1);
    }

    @Test
    void everyReleaseWritesOneEventInTheSameTransactionAndARolledBackChangeWritesNone() throws SQLException {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        long before = events(organization);
        long releasesBefore = releases(admin);

        rule(admin, "NoNegative", "quantity__c < 0", true);
        Response refused = admin.postJson(OBJECTS + "/Order__c/validation-rules", "{\"name\":\"Ghost\","
                + "\"label\":\"G\",\"expression\":\"ghost__c > 1\",\"errorMessage\":\"M\"}");
        admin.postJson("/api/v1/metadata/releases/latest/rollback", "{}");

        assertThat(refused.status()).isEqualTo(409);
        assertThat(releases(admin) - releasesBefore).as("the rule and its rollback").isEqualTo(2);
        assertThat(events(organization) - before).as("one event for each release, none for the refusal").isEqualTo(2);
    }

    @Test
    void theEventWarmsTheCatalogueAndTheArtefactsSoTheFirstQuestionAfterAPublicationHitsTheCache() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        rule(admin, "NoNegative", "quantity__c < 0", true);
        long warmedBefore = warmer.warmedCount();
        long currentBefore = warmer.currentCount();
        long builtBefore = runtime.builtCount();
        cache.clear();

        asOrganization(organization, () -> {
            warmer.handle(published(organization));
            return null;
        });

        assertThat(warmer.warmedCount() - warmedBefore).as("nothing was cached for this version yet").isEqualTo(1);
        assertThat(runtime.builtCount() - builtBefore).as("the compiled rule was made by the handler").isEqualTo(1);
        long hits = cache.hitCount();
        long misses = cache.missCount();
        long built = runtime.builtCount();

        List<CompiledRule> rules = asOrganization(organization, () -> resolver.validationRules("Order__c"));
        asOrganization(organization, () -> metadata.object("Order__c").orElseThrow());

        assertThat(rules).hasSize(1);
        assertThat(cache.missCount() - misses).as("the first request paid for nothing").isZero();
        assertThat(cache.hitCount() - hits).isEqualTo(2);
        assertThat(runtime.builtCount()).isEqualTo(built);

        asOrganization(organization, () -> {
            warmer.handle(published(organization));
            return null;
        });

        assertThat(warmer.currentCount() - currentBefore).as("the same event again changes nothing").isEqualTo(1);
        assertThat(warmer.warmedCount() - warmedBefore).isEqualTo(1);
    }

    @Test
    void aLostEventOrAHandlerThatNeverRunsNeverMakesAnAnswerWrong() {
        Organization organization = organization();
        TestBrowser admin = adminWithOrder(organization);
        rule(admin, "NoNegative", "quantity__c < 0", true);
        // The relay is off in this context, so no event of these releases is ever handled: reads must be right anyway.
        assertThat(asOrganization(organization, () -> resolver.validationRules("Order__c"))).hasSize(1);

        ok(admin.request("DELETE", OBJECTS + "/Order__c/validation-rules/NoNegative__c", null));

        assertThat(asOrganization(organization, () -> resolver.validationRules("Order__c"))).isEmpty();
    }

    @Test
    void anotherOrganizationNeverGetsTheArtefactsOfThisOne() {
        Organization mine = organization();
        Organization theirs = organization();
        TestBrowser admin = adminWithOrder(mine);
        adminWithOrder(theirs);
        rule(admin, "NoNegative", "quantity__c < 0", true);

        assertThat(asOrganization(mine, () -> resolver.validationRules("Order__c"))).hasSize(1);
        assertThat(asOrganization(theirs, () -> resolver.validationRules("Order__c"))).isEmpty();
    }
}
