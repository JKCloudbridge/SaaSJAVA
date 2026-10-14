package app.platform.data.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The words of the search (ADR-0124): the same function for what is stored and what is typed. */
class SearchTokensTest {

    @Test
    void aTextIsCutIntoLowerCaseWordsWithoutAccentsAndWithoutRepeats() {
        assertThat(SearchTokens.wordsOf("José GARCÍA, José!")).containsExactly("jose", "garcia");
    }

    @Test
    void aSingleLetterIsNotAWordButASingleDigitIs() {
        assertThat(SearchTokens.wordsOf("a b 7 cc")).containsExactly("7", "cc");
    }

    @Test
    void anEmailAddressIsKeptWholeAndAsItsParts() {
        Set<String> words = SearchTokens.wordsOf("User-A@Example.test");

        assertThat(words).contains("user-a@example.test", "user", "example", "test");
    }

    @Test
    void aTelephoneNumberIsKeptAsItsPartsAndAsItsDigitsRunTogether() {
        Set<String> words = SearchTokens.wordsOf("+1 (555) 123-4567");

        assertThat(words).contains("555", "123", "4567", "15551234567");
    }

    @Test
    void emptyAndBlankTextHaveNoWords() {
        assertThat(SearchTokens.wordsOf(null)).isEmpty();
        assertThat(SearchTokens.wordsOf("   ")).isEmpty();
        assertThat(SearchTokens.wordsOf("!!! ???")).isEmpty();
    }

    @Test
    void aValueHasAtMostSixtyFourWordsAndNoWordIsLongerThanForty() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            text.append("word").append(index).append(' ');
        }
        text.append("x".repeat(100));

        Set<String> words = SearchTokens.wordsOf(text.toString());

        assertThat(words).hasSizeLessThanOrEqualTo(SearchTokens.MAX_WORDS);
        assertThat(words).allSatisfy(word -> assertThat(word.length()).isLessThanOrEqualTo(SearchTokens.MAX_WORD));
    }

    @Test
    void theLastWordTypedIsABeginningAndTheOthersAreWholeWords() {
        List<SearchTokens.Word> words = SearchTokens.queryWords("José gar", 5);

        assertThat(words).extracting(SearchTokens.Word::text).containsExactly("jose", "gar");
        assertThat(words).extracting(SearchTokens.Word::beginning).containsExactly(false, true);
    }

    @Test
    void onlyTheFirstWordsOfALongSearchAreUsed() {
        assertThat(SearchTokens.queryWords("aa bb cc dd ee ff gg", 3)).hasSize(3);
    }

    @Test
    void aSearchOfSingleLettersHasNoWords() {
        assertThat(SearchTokens.queryWords("a b c", 5)).isEmpty();
        assertThat(SearchTokens.queryWords(null, 5)).isEmpty();
    }

    @Test
    void theUpperBoundOfABeginningIsAboveEveryWordThatStartsWithIt() {
        SearchTokens.Word word = new SearchTokens.Word("jos", true);

        assertThat(word.upperBound().compareTo("jose")).isGreaterThan(0);
        assertThat(word.upperBound().compareTo("josé")).isGreaterThan(0);
        assertThat(word.upperBound().startsWith("jos")).isTrue();
    }

    @Test
    void aWordPrintsOnlyItsShape() {
        assertThat(new SearchTokens.Word("secret", true).toString()).doesNotContain("secret");
    }
}
