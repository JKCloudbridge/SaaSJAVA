package app.platform.sheets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.platform.sheets.internal.FillValueStore.Shape;
import app.platform.sheets.internal.FillValues.Kind;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The statements of the report table are made of fixed fragments (FF2, ADR-0193); no database needed to check them. */
class FillValueStoreSqlTest {

    @Test
    void theKindIsALiteralSoThePartialIndexCanBeUsed() {
        for (Kind kind : Kind.values()) {
            assertThat(FillValueStore.sql(kind, Shape.EQUALS, false)).contains("kind = '" + kind.name() + "'");
        }
    }

    @Test
    void eachKindComparesItsOwnColumnWithTheRightCast() {
        assertThat(FillValueStore.sql(Kind.TEXT, Shape.EQUALS, false)).contains("text_value = :v");
        assertThat(FillValueStore.sql(Kind.NUMBER, Shape.EQUALS, false)).contains("number_value = cast(:v as numeric)");
        assertThat(FillValueStore.sql(Kind.DATE, Shape.ONE_OF, false))
                .contains("date_value = any(cast(:vs as date[]))");
        assertThat(FillValueStore.sql(Kind.BOOLEAN, Shape.EQUALS, false))
                .contains("boolean_value = cast(:v as boolean)");
    }

    @Test
    void aRangeMayLeaveEitherBoundOut() {
        assertThat(FillValueStore.sql(Kind.NUMBER, Shape.FROM, false)).contains("number_value >= ")
                .doesNotContain("<=");
        assertThat(FillValueStore.sql(Kind.NUMBER, Shape.TO, false)).contains("number_value <= ")
                .doesNotContain(">=");
        assertThat(FillValueStore.sql(Kind.DATE, Shape.BOTH, false)).contains("date_value >= ").contains("<=");
    }

    @Test
    void thePageContinuesAfterTheLastFillInAndNeverByAnOffset() {
        String first = FillValueStore.sql(Kind.TEXT, Shape.EQUALS, false);
        String next = FillValueStore.sql(Kind.TEXT, Shape.EQUALS, true);

        assertThat(first).doesNotContain(":after").doesNotContain("offset");
        assertThat(next).contains("fill_id < :after").endsWith("order by fill_id desc limit :limit");
    }

    @Test
    void anArrayQuotesEscapesAndKeepsNulls() {
        assertThat(FillValueStore.array(Arrays.asList("a\"b", null, "c\\d")))
                .isEqualTo("{\"a\\\"b\",NULL,\"c\\\\d\"}");
    }

    @Test
    void theArchiveAgeCannotBeShorterThanThirtyDays() {
        assertThatThrownBy(() -> new FillArchiveProperties(true, Duration.ofDays(29), Duration.ofMinutes(10), 50))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FillArchiveProperties(true, Duration.ofDays(30), Duration.ofMinutes(10), 50).batchSize())
                .isEqualTo(50);
    }
}
