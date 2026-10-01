package app.platform.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platformapi.ApiException;
import app.platformapi.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantSlugTest {

    @ParameterizedTest
    @ValueSource(strings = {"tenant-a", "abc", "a1b", "acme-corp-2",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "a-b-c-d"})
    void acceptsLowerCaseWordsDigitsAndSingleHyphens(String slug) {
        assertThat(TenantSlug.of(slug).value()).isEqualTo(slug);
        assertThat(TenantSlug.problem(slug)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "x1", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "Tenant-a", "TENANT",
        "1abc", "-abc", "abc-", "ab--cd",
        "ab_cd", "ab.cd", "ab cd", "tenantä", "ab/cd", "ab:cd", "", " abc", "abc "})
    void rejectsEverythingElse(String slug) {
        assertThat(TenantSlug.problem(slug)).isPresent();
        assertThatThrownBy(() -> TenantSlug.of(slug)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(e.fields()).containsKey("slug");
        });
    }

    @Test
    void nullIsRequired() {
        assertThat(TenantSlug.problem(null)).contains("Is required.");
    }

    @Test
    void reservedNamesAreNotAvailableAsTenantSlugs() {
        for (String reserved : TenantSlug.RESERVED) {
            assertThat(TenantSlug.isReserved(reserved)).isTrue();
            // Reserved names that happen to be well formed are refused; those too short are refused on format.
            assertThat(TenantSlug.problem(reserved)).as(reserved).isPresent();
        }
        assertThat(TenantSlug.problem("admin")).contains("Is not available.");
        assertThat(TenantSlug.problem("www")).contains("Is not available.");
    }

    @Test
    void theReservedNamesIncludeEveryNameThePlatformUsesItself() {
        assertThat(TenantSlug.RESERVED).contains("www", "api", "app", "admin", "platform", "support", "mail", "static",
                "assets", "status", "docs", "help", "login", "auth", "billing", "localhost", "internal", "root",
                "system", "tenant", "tenants");
    }

    @Test
    void theExampleTenantsOfTheProjectAreAcceptable() {
        assertThat(TenantSlug.problem("tenant-a")).isEmpty();
        assertThat(TenantSlug.problem("tenant-b")).isEmpty();
    }

    @Test
    void aSlugReadFromTheDatabaseOnlyNeedsTheFormatSoItCanAlwaysBeRebuilt() {
        assertThat(new TenantSlug("www").value()).isEqualTo("www");
        assertThatThrownBy(() -> new TenantSlug("Bad Slug")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorTextsNeverEchoTheRejectedValue() {
        String secret = "user-a@example.test";

        assertThat(TenantSlug.problem(secret).orElseThrow()).doesNotContain("user-a", "example");
    }
}
