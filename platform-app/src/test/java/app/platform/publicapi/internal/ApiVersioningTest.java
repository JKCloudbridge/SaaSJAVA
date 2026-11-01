package app.platform.publicapi.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/**
 * The versioning and deprecation policy of the public API as a tested fact (Sprint 30, ADR-0247): the version header is
 * always there, a deprecated operation announces itself with three headers, and a deprecation that gives less than 12
 * months between the announcement and the sunset cannot even be registered.
 */
class ApiVersioningTest {

    private static final LocalDate ANNOUNCED = LocalDate.of(2027, 1, 15);

    @Test
    void everyAnswerNamesTheVersionAndNothingMoreWhenNothingIsDeprecated() {
        Map<String, String> headers = ApiVersioning.shipped().headersFor("GET", "/api/public/v1/objects");

        assertThat(headers).containsExactly(Map.entry("API-Version", "v1"));
    }

    @Test
    void aDeprecatedOperationAnnouncesItselfWithTheDayTheSunsetAndTheSuccessor() {
        ApiVersioning versioning = new ApiVersioning(List.of(new ApiVersioning.Deprecation("POST",
                "/api/public/v1/objects/{object}/records/query", ANNOUNCED, ANNOUNCED.plusMonths(14),
                "/api/public/v2/objects/{object}/records/search")));

        Map<String, String> headers = versioning.headersFor("POST", "/api/public/v1/objects/Employee__c/records/query");

        assertThat(headers.get("API-Version")).isEqualTo("v1");
        assertThat(headers.get("Deprecation")).isEqualTo("@1799971200");
        assertThat(headers.get("Sunset")).isEqualTo("Wed, 15 Mar 2028 00:00:00 GMT");
        assertThat(headers.get("Link"))
                .isEqualTo("</api/public/v2/objects/{object}/records/search>; rel=\"successor-version\"");
    }

    @Test
    void anotherOperationOrAnotherMethodIsNotAnnounced() {
        ApiVersioning versioning = new ApiVersioning(List.of(new ApiVersioning.Deprecation("POST",
                "/api/public/v1/objects/{object}/records/query", ANNOUNCED, ANNOUNCED.plusMonths(12), "/next")));

        assertThat(versioning.headersFor("GET", "/api/public/v1/objects/Employee__c/records/query"))
                .containsOnlyKeys("API-Version");
        assertThat(versioning.headersFor("POST", "/api/public/v1/objects/Employee__c/records/bulk"))
                .containsOnlyKeys("API-Version");
        assertThat(versioning.headersFor("POST", "/api/public/v1/objects//records/query"))
                .containsOnlyKeys("API-Version");
    }

    @Test
    void aSunsetExactlyTwelveMonthsAfterTheAnnouncementIsAccepted() {
        new ApiVersioning.Deprecation("GET", "/x", ANNOUNCED, ANNOUNCED.plusMonths(12), "/y");
    }

    @Test
    void aSunsetADayShortOfTwelveMonthsIsRefused() {
        assertThatThrownBy(() -> new ApiVersioning.Deprecation("GET", "/x", ANNOUNCED,
                ANNOUNCED.plusMonths(12).minusDays(1), "/y")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("12 months");
    }

    @Test
    void aDeprecationThatNamesNotEverythingIsRefused() {
        assertThatThrownBy(() -> new ApiVersioning.Deprecation("GET", "/x", ANNOUNCED, null, "/y"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiVersioning.Deprecation("GET", "/x", ANNOUNCED, ANNOUNCED.plusYears(2), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void noSunsetEarlierThanTheAnnouncementPlusTheMinimumCanBeRegistered(
            @ForAll @IntRange(min = 0, max = 3000) int days,
            @ForAll @IntRange(min = 0, max = 4000) int gap) {
        LocalDate announced = LocalDate.of(2027, 1, 1).plusDays(days);
        LocalDate sunset = announced.plusDays(gap);
        boolean allowed = !sunset.isBefore(announced.plusMonths(ApiVersioning.MINIMUM_NOTICE_MONTHS));
        try {
            new ApiVersioning.Deprecation("GET", "/x", announced, sunset, "/y");
            assertThat(allowed).as("registered %s -> %s", announced, sunset).isTrue();
        } catch (IllegalArgumentException e) {
            assertThat(allowed).as("refused %s -> %s", announced, sunset).isFalse();
        }
    }

    @Test
    void theRegistryThatShipsIsValidAndItsPolicyIsAtLeastTwelveMonths() {
        assertThat(ApiVersioning.MINIMUM_NOTICE_MONTHS).isGreaterThanOrEqualTo(12);
        ApiVersioning.shipped().deprecations().forEach(deprecation -> assertThat(deprecation.sunsetOn())
                .isAfterOrEqualTo(deprecation.announcedOn().plusMonths(ApiVersioning.MINIMUM_NOTICE_MONTHS)));
    }
}
