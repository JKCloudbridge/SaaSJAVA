package app.platformapi;

import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * What a mapping can be made of (Sprint 28, ADR-0218): the screens draw their choices from this and hold no list of
 * their own, so a conversion or a rule the server adds is on the screen the day it ships.
 *
 * @param conflicts the rules for a field that goes both ways when both sides changed it
 * @param directions the ways a field can go
 * @param externalTypes what the external side can carry
 * @param conversions the conversions that need no expression
 */
public record MappingCatalogueView(@NotNull List<MappingChoice> conflicts, @NotNull List<MappingChoice> directions,
        @NotNull List<MappingChoice> externalTypes, @NotNull List<MappingChoice> conversions) {

    public MappingCatalogueView {
        conflicts = List.copyOf(conflicts);
        directions = List.copyOf(directions);
        externalTypes = List.copyOf(externalTypes);
        conversions = List.copyOf(conversions);
    }
}
