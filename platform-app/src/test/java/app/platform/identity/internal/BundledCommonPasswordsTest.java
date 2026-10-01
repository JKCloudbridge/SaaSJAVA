package app.platform.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BundledCommonPasswordsTest {

    private final BundledCommonPasswords list = new BundledCommonPasswords();

    @Test
    void theBundledListHasTenThousandEntries() {
        assertThat(list.size()).isBetween(9_900, 10_100);
    }

    @Test
    void caseDoesNotMatterAndTrailingDigitsAndPunctuationAreStripped() {
        assertThat(list.contains("PassWord")).isTrue();
        assertThat(list.contains("password!!")).isTrue();
        assertThat(list.contains("qwerty2024")).isTrue();
    }

    @Test
    void anUnusualPasswordIsNotOnTheList() {
        assertThat(list.contains("violet-lantern-ocean-77")).isFalse();
    }
}
