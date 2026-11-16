package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import app.platform.data.DataRecord;
import app.platform.data.QueriedRecord;
import app.platform.data.QueryFilter;
import app.platform.data.QueryResult;
import app.platform.data.RecordQuery;
import app.platform.data.Records;
import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryRequest;
import app.platform.inspection.internal.ManagementHomeViews.ManagementSummaryView;
import app.platform.inspection.internal.TemplateAccess.Caller;
import app.platform.metadata.Metadata;
import app.platform.metadata.ObjectDefinition;
import app.platform.security.Decision;
import app.platform.security.Decisions;
import app.platform.security.ObjectAction;
import app.platform.sharedkernel.approval.ApprovalDecisions;
import app.platform.sharedkernel.approval.ApprovalStarter;
import app.platform.sharedkernel.solution.ApplicationSettings;
import app.platformapi.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * What the dashboard that compares the plants asks the record store, without a database (MC3, ADR-0262): every question
 * it puts to the records of the fill-in or the template object is narrowed to its own application, so a fill-in of
 * Document Management or the Quality Management System can never be counted by it, whatever else is true; and the
 * numbers it makes of the header facts the store gives are the numbers the arithmetic says.
 */
class ManagementHomeNarrowingTest {

    private static final String APPLICATION = "management-core";
    private static final RecordSet SET = new RecordSet("InspectionTemplate", "InspectionFill",
            "InspectionChangeRequest", "document",
            Map.of("dashboard", "management", "monthlyFillIn", "true", "routedByNumber", "true"), APPLICATION);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
    private static final Caller CALLER = new Caller(UUID.randomUUID(), UUID.randomUUID());

    private final TemplateAccess access = mock(TemplateAccess.class);
    private final Metadata metadata = mock(Metadata.class);
    private final Decisions decisions = mock(Decisions.class);
    private final Records records = mock(Records.class);
    private final DepartmentAudiences departments = mock(DepartmentAudiences.class);
    private final PlantPublicationStore plants = mock(PlantPublicationStore.class);
    private final ApprovalStarter starter = mock(ApprovalStarter.class);
    private final ApprovalDecisions approvals = mock(ApprovalDecisions.class);
    private final ApplicationSettings settings = mock(ApplicationSettings.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ManagementHome home = new ManagementHome(access, metadata, decisions, records, departments, plants,
            starter, approvals, settings, new SimpleMeterRegistry(), QualityHomeProperties.standard(), CLOCK,
            transactions);

    private void everythingMayBeRead(List<QueriedRecord> fills) {
        when(access.member()).thenReturn(CALLER);
        when(metadata.object(anyString())).thenReturn(Optional.of(mock(ObjectDefinition.class)));
        when(decisions.can(any(UUID.class), anyString(), any(ObjectAction.class))).thenReturn(Decision.allow());
        when(starter.available(anyString())).thenReturn(true);
        when(settings.value(eq(APPLICATION), anyString())).thenAnswer(call -> switch (call.<String>getArgument(1)) {
            case "scoreLimit", "excellentAbove" -> OptionalInt.of(85);
            case "goodAbove" -> OptionalInt.of(65);
            default -> OptionalInt.empty();
        });
        when(departments.labels(anyString())).thenReturn(Map.of("P1", "Plant 1", "P2", "Plant 2"));
        when(departments.placesOf(any(UUID.class))).thenReturn(List.of());
        when(plants.livePlantCodes(anyCollection())).thenReturn(Set.of("P1", "P2"));
        when(approvals.pendingSteps(anyString(), anyCollection())).thenReturn(Map.of());
        when(records.query(eq("InspectionFill"), any(RecordQuery.class)))
                .thenReturn(new QueryResult(fills, null, 200));
        when(records.query(eq("InspectionTemplate"), any(RecordQuery.class)))
                .thenReturn(new QueryResult(List.of(), null, 200));
    }

    private static QueriedRecord fill(String plant, String period, String score, String status, String result) {
        Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("templateId", UUID.randomUUID().toString());
        fields.put("name", "Plant activity");
        fields.put("plant", plant);
        fields.put("period", period);
        fields.put("score", score == null ? null : new BigDecimal(score));
        fields.put("status", status);
        fields.put("result", result);
        fields.values().removeIf(java.util.Objects::isNull);
        DataRecord record = new DataRecord(UUID.randomUUID(), "InspectionFill", null, UUID.randomUUID(), fields, 0,
                Instant.now(), UUID.randomUUID(), Instant.now(), UUID.randomUUID(), null, null);
        return new QueriedRecord(record, Map.of());
    }

    /** Whether the filter, anywhere in its tree, says that the field is exactly this value. */
    private static boolean says(QueryFilter filter, String field, String value) {
        if (filter == null) {
            return false;
        }
        if (filter.op() == QueryFilter.Op.EQ && field.equals(filter.field()) && filter.values().contains(value)) {
            return true;
        }
        return filter.items().stream().anyMatch(item -> says(item, field, value));
    }

    @Test
    void everyQuestionPutToTheRecordsOfTheFillInsOrTheTemplatesIsNarrowedToTheApplication() {
        everythingMayBeRead(List.of());

        home.summary(SET, null);

        ArgumentCaptor<RecordQuery> asked = ArgumentCaptor.forClass(RecordQuery.class);
        verify(records, org.mockito.Mockito.atLeast(4)).query(anyString(), asked.capture());
        List<RecordQuery> queries = asked.getAllValues();
        assertThat(queries).as("the months, the Action plans, the waiting and the templates")
                .hasSizeGreaterThanOrEqualTo(4);
        for (RecordQuery query : queries) {
            assertThat(says(query.filter(), "application", APPLICATION))
                    .as("a question that is not narrowed to the application would count other applications' records")
                    .isTrue();
        }
    }

    @Test
    void noQuestionIsAskedOfAnObjectTheCallerMayNotReadAndTheSectionsAreNotDrawnNotZero() {
        everythingMayBeRead(List.of());
        when(decisions.can(any(UUID.class), eq("InspectionFill"), any(ObjectAction.class)))
                .thenReturn(Decision.deny(Decision.Reason.OBJECT_NOT_ALLOWED));

        ManagementSummaryView view = home.summary(SET, null);

        verify(records, org.mockito.Mockito.never()).query(eq("InspectionFill"), any(RecordQuery.class));
        assertThat(view.tiles().plantsSubmitted()).isNull();
        assertThat(view.tiles().averageScore()).isNull();
        assertThat(view.tiles().waitingForReview()).isNull();
        assertThat(view.tiles().actionPlansOpen()).isNull();
        assertThat(view.charts().scoresByPlant()).isNull();
        assertThat(view.problems()).isEmpty();
    }

    @Test
    void theNumbersAreMadeOfTheHeadersTheStoreGaveAndNothingElse() {
        everythingMayBeRead(List.of(fill("P1", "2026-11", "90", "APPROVED", "PASS"),
                fill("P2", "2026-11", "70.5", "APPROVED", "ACTION_PLAN"),
                fill("P2", "2026-10", "80", "SUBMITTED", "ACTION_PLAN")));

        ManagementSummaryView view = home.summary(SET, new ManagementSummaryRequest("this", 3, null, null));

        assertThat(view.month()).isEqualTo("2026-11");
        assertThat(view.previousMonth()).isEqualTo("2026-10");
        assertThat(view.months()).containsExactly("2026-09", "2026-10", "2026-11");
        assertThat(view.tiles().plantsSubmitted().value()).isEqualTo(2.0);
        assertThat(view.tiles().plantsSubmitted().whole()).isEqualTo(2.0);
        assertThat(view.tiles().plantsSubmitted().previous()).isEqualTo(1.0);
        assertThat(view.tiles().averageScore().value()).as("(90 + 70.5) / 2").isEqualTo(80.25);
        assertThat(view.tiles().plantsBelowLimit().value()).isEqualTo(1.0);
        assertThat(view.tiles().plantsBelowLimit().extra()).as("no Action plan answers it").isEqualTo(1.0);
        assertThat(view.charts().scoresByPlant()).extracting(ManagementHomeViews.PlantScore::plant)
                .containsExactly("P1", "P2");
        assertThat(view.legend().limit()).isEqualTo(85);
        assertThat(view.legend().goodAbove()).isEqualTo(65);
        assertThat(view.legend().excellentAbove()).isEqualTo(85);
        assertThat(view.problems()).isEmpty();
    }

    @Test
    void aSectionThatFailsIsNamedAndTheOthersStillAnswer() {
        everythingMayBeRead(List.of(fill("P1", "2026-11", "90", "APPROVED", "PASS")));
        when(approvals.pendingSteps(anyString(), anyCollection())).thenThrow(new IllegalStateException("boom"));

        ManagementSummaryView view = home.summary(SET, null);

        assertThat(view.problems()).containsExactly("waiting");
        assertThat(view.tiles().waitingForReview()).as("not drawn, not a zero").isNull();
        assertThat(view.tiles().waitingForTopManagement()).isNull();
        assertThat(view.tiles().plantsSubmitted().value()).as("the rest is still answered").isEqualTo(1.0);
        assertThat(view.charts().scoresByPlant()).as("the plants the rows name").hasSize(1);
    }

    @Test
    void anApplicationThatDoesNotAskForThisDashboardHasNoSuchPage() {
        everythingMayBeRead(List.of());
        RecordSet plain = new RecordSet("InspectionTemplate", "InspectionFill", "InspectionChangeRequest", "document");

        assertThatThrownBy(() -> home.summary(plain, null)).isInstanceOf(ApiException.class);
        verify(records, org.mockito.Mockito.never()).query(anyString(), any(RecordQuery.class));
    }

    @Test
    void aValueThatIsNotOfferedIsRefusedBeforeAnyNumberIsMade() {
        everythingMayBeRead(List.of(fill("P1", "2026-11", "90", "APPROVED", "PASS")));
        List<ManagementSummaryRequest> bad = new ArrayList<>();
        bad.add(new ManagementSummaryRequest("2027-05", null, null, null));
        bad.add(new ManagementSummaryRequest("nonsense", null, null, null));
        bad.add(new ManagementSummaryRequest(null, 7, null, null));
        bad.add(new ManagementSummaryRequest(null, null, "P9", null));
        bad.add(new ManagementSummaryRequest(null, null, null, UUID.randomUUID()));

        for (ManagementSummaryRequest request : bad) {
            assertThatThrownBy(() -> home.summary(SET, request)).as(request.toString())
                    .isInstanceOf(ApiException.class);
        }
    }
}
