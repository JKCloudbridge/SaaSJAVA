package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a screen needs to draw the record page of one object for the signed-in member (Sprint 20, ADR-0131): presents
 * only, decides nothing. {@code GET /api/v1/objects/{object}/record-page}.
 *
 * <p>The page is a header card, then the blocks of the main region in the order the administrator wrote them, with
 * the tab strip standing where the {@code TABS} component stood: {@code above} are the blocks written before it,
 * {@code below} those after it (all of them when the page has no tabs), {@code sidebar} the blocks of the side
 * region. When the object has no record page configured for this member, the platform's own page is described
 * instead ({@code apiName} null): a {@code Content} tab and, when other objects point at this one, a
 * {@code Related Records} tab, so every object has a working record view with no configuration.
 *
 * @param apiName the record page that was resolved, or null for the platform's own page
 * @param layout the page layout the details show: the one the page names, or null when the member's own resolution
 *        (profile and record type, ADR-0071) decides; pass it to {@code GET .../form} as {@code layout}
 * @param header the title and the fields to show beside it
 * @param above blocks of the main region written before the tabs
 * @param tabs the tabs to show, in order, each already filtered to what the caller may reach
 * @param below blocks of the main region written after the tabs
 * @param sidebar blocks of the side region
 * @param actions the actions to offer, already decided for the caller (and the record, when one is open)
 */
public record RecordPageDescriptorView(String apiName, String layout, @NotNull RecordPageHeaderView header,
        @NotNull List<RecordPageBlockView> above, @NotNull List<RecordPageTabView> tabs,
        @NotNull List<RecordPageBlockView> below, @NotNull List<RecordPageBlockView> sidebar,
        @NotNull List<RecordPageActionView> actions) {

    public RecordPageDescriptorView {
        above = List.copyOf(above);
        tabs = List.copyOf(tabs);
        below = List.copyOf(below);
        sidebar = List.copyOf(sidebar);
        actions = List.copyOf(actions);
    }
}
