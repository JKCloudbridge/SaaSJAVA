package app.platform.metadata.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platformapi.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How a typed name becomes a permanent API name, and how labels are cleaned (ADR-0058). */
class NameRulesTest {

    @Test
    void anObjectNameGetsTheCustomEnding() {
        assertThat(NameRules.objectApiName("Employee")).isEqualTo("Employee__c");
        assertThat(NameRules.objectApiName("  Fleet_Vehicle ")).isEqualTo("Fleet_Vehicle__c");
        assertThat(NameRules.objectApiName("Q3Report")).isEqualTo("Q3Report__c");
    }

    @Test
    void aFieldNameGetsTheCustomEnding() {
        assertThat(NameRules.fieldApiName("salary")).isEqualTo("salary__c");
        assertThat(NameRules.fieldApiName("joiningDate")).isEqualTo("joiningDate__c");
        assertThat(NameRules.fieldApiName("plate_number")).isEqualTo("plate_number__c");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "employee", "1Employee", "Employee Name", "Employee-Name", "Employee__Name",
            "Employee_", "_Employee", "Employé", "Employee__c", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void aBadObjectNameIsRefusedInWords(String typed) {
        assertThatThrownBy(() -> NameRules.objectApiName(typed)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fields()).containsKey("name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Salary", "9salary", "sal ary", "sal-ary", "sal__ary", "salary_", "salary__c",
            "id__c", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void aBadFieldNameIsRefusedInWords(String typed) {
        assertThatThrownBy(() -> NameRules.fieldApiName(typed)).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fields()).containsKey("name"));
    }

    @Test
    void theEndingNeverNeedsToBeTyped() {
        assertThatThrownBy(() -> NameRules.objectApiName("Employee__c")).hasMessageContaining("The request is invalid")
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).fields().get("name").get(0)).contains("__c"));
    }

    @Test
    void labelsAreTrimmedAndBounded() {
        assertThat(NameRules.label("label", "  Employee  ")).isEqualTo("Employee");
        assertThatThrownBy(() -> NameRules.label("label", "   ")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NameRules.label("label", "x".repeat(81))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NameRules.label("label", "bad\nlabel")).isInstanceOf(ApiException.class);
        assertThat(NameRules.description(null)).isEmpty();
        assertThatThrownBy(() -> NameRules.description("x".repeat(501))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> NameRules.description("bad\u0000text")).isInstanceOf(ApiException.class);
    }
}
