package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.data.QueryPlan.After;
import app.platformapi.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** The cursor of a page of a query (Sprint 16, ADR-0104): it comes back exactly, and it belongs to one query. */
class QueryCursorTest {

    private static final UUID ID = UUID.fromString("01a1311b-3b3f-79d7-8c8e-effddcd1239e");
    private static final String SIGNATURE = "0123456789abcdef";

    private final QueryCursor cursors = new QueryCursor(new RecordJson());

    @Provide
    Arbitrary<List<String>> sortValues() {
        return Arbitraries.strings().alpha().numeric().withChars(' ', ':', '-', '.', '+').ofMaxLength(40)
                .injectNull(0.3).list().ofMaxSize(2);
    }

    @Property(tries = 200)
    void aCursorComesBackExactlyWhatWentIn(@ForAll("sortValues") List<String> values) {
        String cursor = cursors.encode(SIGNATURE, values, ID);

        After after = cursors.decode(cursor, SIGNATURE, values.size());

        assertThat(after.values()).containsExactlyElementsOf(values);
        assertThat(after.id()).isEqualTo(ID);
    }

    @Test
    void aCursorOfAnotherQueryIsRefused() {
        String cursor = cursors.encode(SIGNATURE, List.of("a"), ID);

        assertThatThrownBy(() -> cursors.decode(cursor, "ffffffffffffffff", 1)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).fields()).containsKey("cursor"));
    }

    @Test
    void aCursorWithTheWrongNumberOfValuesIsRefused() {
        String cursor = cursors.encode(SIGNATURE, List.of("a", "b"), ID);

        assertThatThrownBy(() -> cursors.decode(cursor, SIGNATURE, 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> cursors.decode(cursor, SIGNATURE, 0)).isInstanceOf(ApiException.class);
    }

    @Test
    void anythingThatIsNotACursorThisApiMadeIsRefusedWithoutAnErrorOfAnotherKind() {
        String notJson = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes(StandardCharsets.UTF_8));
        String wrongShape = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"q\":\"x\",\"v\":5,\"i\":3}".getBytes(StandardCharsets.UTF_8));
        String badId = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"q\":\"" + SIGNATURE + "\",\"v\":[],\"i\":\"nope\"}").getBytes(StandardCharsets.UTF_8));
        String numberValue = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"q\":\"" + SIGNATURE + "\",\"v\":[5],\"i\":\"" + ID + "\"}").getBytes(StandardCharsets.UTF_8));

        for (String junk : Arrays.asList("", "!!!", "a".repeat(5000), notJson, wrongShape, badId, numberValue, null)) {
            assertThatThrownBy(() -> cursors.decode(junk, SIGNATURE, 0)).as("cursor " + junk)
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void theValuesAreInTheCursorOnlyBecauseTheyAreSortKeysTheMemberCouldRead() {
        List<String> values = new ArrayList<>();
        values.add("visible sort value");

        String cursor = cursors.encode(SIGNATURE, values, ID);

        assertThat(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8))
                .contains("visible sort value").doesNotContain("password");
    }
}
