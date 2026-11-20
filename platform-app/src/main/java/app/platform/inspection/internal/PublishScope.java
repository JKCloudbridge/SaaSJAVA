package app.platform.inspection.internal;

import app.platform.inspection.internal.AudienceChoices.Place;
import app.platform.inspection.internal.TemplateViews.AudiencePick;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which locations a person may publish a template to (FA15, ADR-0265). It is the one rule: the publish asks it before
 * anything is shared and the lifecycle view asks it to tell the screen what to offer, so the screen never decides.
 *
 * <p>The people of the Plant QMS group publish only to the locations the approval step chose (the suggestion, as it
 * stands today). A person who holds the ability to publish to any location is not limited, and a template with no
 * suggestion at all (a process with no choice step, an older template) is not limited either. When a suggestion was
 * made but none of its places is offered any more, a limited person cannot publish anything; somebody with the ability
 * can. <strong>Only the locations limit; the departments of a suggestion are hints</strong> because Corporate said a
 * department is optional.
 *
 * <p>A pure rule with no database, tested alone. The codes are the organization's own text, so nothing prints them.
 */
final class PublishScope {

    /** The words of a pick outside the locations that were chosen. They repeat no code. */
    static final String OUTSIDE = "This location was not chosen for this document.";
    /** The words when a suggestion was made and none of its places is offered any more. */
    static final String NONE_LEFT = "No location chosen for this document is offered any more. Ask somebody who may "
            + "publish to any location.";

    private static final PublishScope UNLIMITED = new PublishScope(false, Set.of());

    private final boolean limited;
    private final Set<String> locations;

    private PublishScope(boolean limited, Set<String> locations) {
        this.limited = limited;
        this.locations = locations;
    }

    /** No limit: the person publishes anywhere. */
    static PublishScope unlimited() {
        return UNLIMITED;
    }

    /**
     * The scope of one person for one version.
     *
     * @param mayAnywhere whether the person holds the ability to publish to any location
     * @param suggestion the suggestion as it stands today (places still offered, and how many were dropped)
     */
    static PublishScope of(boolean mayAnywhere, AudienceSuggestions.Held suggestion) {
        boolean suggested = !suggestion.places().isEmpty() || suggestion.dropped() > 0;
        if (mayAnywhere || !suggested) {
            return UNLIMITED;
        }
        Set<String> chosen = new LinkedHashSet<>();
        for (Place place : suggestion.places()) {
            chosen.add(place.location());
        }
        return new PublishScope(true, Set.copyOf(chosen));
    }

    /** Whether the person is limited to the locations of a suggestion. */
    boolean limited() {
        return limited;
    }

    /** The locations a limited person may publish to; empty when none is left. Not meaningful when unlimited. */
    Set<String> locations() {
        return locations;
    }

    /** Whether the person is limited and no place of the suggestion is offered any more. */
    boolean noneLeft() {
        return limited && locations.isEmpty();
    }

    /** Whether a location may be published to. */
    boolean allows(String location) {
        return !limited || locations.contains(location);
    }

    /** The positions of the picks whose location is outside the scope, in the order sent; empty when unlimited. */
    List<Integer> outside(List<AudiencePick> picks) {
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < picks.size(); index++) {
            if (!allows(picks.get(index).location())) {
                positions.add(index);
            }
        }
        return positions;
    }

    /** Prints nothing of the locations. */
    @Override
    public String toString() {
        return limited ? "PublishScope[limited]" : "PublishScope[unlimited]";
    }
}
