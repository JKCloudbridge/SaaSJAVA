package app.platform.inspection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The rule of ADR-0266 held by a test: every day the plant board reads (the day a score was stamped and the day an Action
 * plan was sent) is written, and judged, from the one injected clock. A statement that asks the database for the time, or
 * a call that asks the machine for it, would make the days impossible to control in a test and impossible to move in one
 * place, so none of the classes that write, read or judge these stamps may contain one.
 */
class PlantBoardStampsUseTheClockTest {

    private static final Path INTERNAL = Path.of("src/main/java/app/platform/inspection/internal");
    private static final List<String> CLASSES = List.of("FillApprovalHooks.java", "FillHeaders.java",
            "FillPeriods.java", "PlantBoardNumbers.java", "ManagementBoardViews.java", "ManagementNumbers.java");
    private static final Pattern DATABASE_TIME = Pattern.compile(
            "(?i)\\b(now\\(\\)|current_timestamp|current_date|clock_timestamp\\(\\)|statement_timestamp\\(\\))");
    private static final Pattern MACHINE_TIME = Pattern.compile(
            "\\b(Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime|YearMonth)\\.now\\(\\s*\\)|"
                    + "System\\.currentTimeMillis");

    private static String source(String name) throws IOException {
        return Files.readString(INTERNAL.resolve(name));
    }

    @Test
    void noClassThatWritesReadsOrJudgesAStampAsksTheDatabaseOrTheMachineForTheTime() throws IOException {
        for (String name : CLASSES) {
            String text = source(name);
            assertThat(DATABASE_TIME.matcher(text).find()).as(name + " asks the database for the time").isFalse();
            assertThat(MACHINE_TIME.matcher(text).find()).as(name + " asks the machine for the time").isFalse();
        }
    }

    @Test
    void theDayAStampIsWrittenWithComesFromTheClockOfTheMonthRuleAndNowhereElse() throws IOException {
        assertThat(source("FillPeriods.java")).contains("LocalDate.now(clock.withZone(ZoneOffset.UTC))");
        assertThat(source("FillApprovalHooks.java")).contains("periods.today()");
        assertThat(source("ManagementHome.java")).contains("clock.instant()");
    }

    @Test
    void theBoardWritesNoRecordAndNoStatement() throws IOException {
        String board = source("PlantBoardNumbers.java");
        assertThat(board).doesNotContain("jdbc").doesNotContain("BackgroundWrites").doesNotContain("Records ");
    }
}
