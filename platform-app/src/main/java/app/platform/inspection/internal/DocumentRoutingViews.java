package app.platform.inspection.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * What the routing endpoints send and take (MC1.1, ADR-0259). The Identification and the Part A are the organization's
 * own text: they are shown on the screen of the person who manages the rules and never reach a log or an audit record.
 * Everything here is for the screen to draw; every request that follows is decided again by the server.
 */
public final class DocumentRoutingViews {

    private DocumentRoutingViews() {
    }

    /**
     * One rule of the organization.
     *
     * @param id the rule
     * @param identification the code of the Identification
     * @param partA the Part A, or an empty text when the rule is for every Part A
     * @param application the key of the application the documents also belong to
     * @param applicationLabel its name for people; null when the organization does not have the application any more
     *        (the rule then does nothing until the application is given again, and can be removed)
     * @param available whether the organization has the application now
     * @param version the version to send when changing or removing the rule
     */
    public record RoutingRuleView(@NotNull UUID id, @NotNull String identification, @NotNull String partA,
            @NotNull String application, String applicationLabel, @NotNull Boolean available,
            @NotNull Long version) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "RoutingRuleView[redacted]";
        }
    }

    /**
     * An application a document can be routed to.
     *
     * @param application the key to send
     * @param label its name for people
     */
    public record RoutingApplicationView(@NotNull String application, @NotNull String label) {
    }

    /**
     * A value of the Identification list the organization owns.
     *
     * @param value the stored code
     * @param label its name for people
     * @param active whether new documents can choose it
     */
    public record RoutingIdentificationView(@NotNull String value, @NotNull String label, @NotNull Boolean active) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "RoutingIdentificationView[redacted]";
        }
    }

    /**
     * The rules and what a rule can name.
     *
     * @param rules every rule, in the order a person reads them
     * @param applications the applications the organization has whose documents come from Document Management; a
     *        rule can name only these. Empty when there is none, and the screen then offers nothing
     * @param identifications the Identification values of the documents
     */
    public record RoutingView(@NotNull List<RoutingRuleView> rules,
            @NotNull List<RoutingApplicationView> applications,
            @NotNull List<RoutingIdentificationView> identifications) {

        /** Copies the lists. */
        public RoutingView {
            rules = List.copyOf(rules);
            applications = List.copyOf(applications);
            identifications = List.copyOf(identifications);
        }
    }

    /**
     * Makes a rule, or changes the application of the rule of that combination.
     *
     * @param identification the code of the Identification, from the organization's list
     * @param partA the Part A (capital letters, digits and hyphens, at most 10), or empty for every Part A
     * @param application the key of an application of the list the view gave
     * @param version the version of the rule being changed, or null to make a new rule (refused when the combination
     *        already has one)
     */
    public record SaveRoutingRuleRequest(@NotBlank @Size(max = 10) String identification,
            @Size(max = 10) String partA, @NotBlank @Size(max = 60) String application, Long version) {

        /** Prints nothing typed. */
        @Override
        public String toString() {
            return "SaveRoutingRuleRequest[redacted]";
        }
    }
}
