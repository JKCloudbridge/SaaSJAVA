package app.platform.sharedkernel.solution;

import java.util.List;
import java.util.Optional;

/**
 * Which objects play which part in the applications the shipped solutions make (FA13B, ADR-0226). The module that loads
 * the shipped solutions implements it from their files; the module that owns the functions of such an application
 * asks it, so neither depends on the other (the same inversion as {@link SheetOwnerDeclarations} and
 * {@link NumberSeriesDeclarations}). The declaration is data: it holds no code and an organization cannot change it.
 *
 * <p>Two applications may declare the very same objects (the second shows the first one's records); they may not
 * declare an object under another part or in another combination.
 */
public interface ObjectRoleDeclarations {

    /**
     * Every application that declares its parts, in the order the applications stand in the grid. Fixed while the
     * application runs; says nothing about any organization.
     */
    List<ObjectRoles> declared();

    /**
     * The parts of an application the organization of the current tenant context has: the application is declared and
     * its plan feature is on. Empty for an unknown application, for one the organization lacks and for the platform
     * address, all alike, so a caller cannot tell them apart.
     */
    Optional<ObjectRoles> available(String application);
}
