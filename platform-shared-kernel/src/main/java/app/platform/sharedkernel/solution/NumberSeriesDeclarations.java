package app.platform.sharedkernel.solution;

import java.util.List;

/**
 * The number series the platform itself ships, as the record store collects them (Functional track FA2A, ADR-0176,
 * ADR-0184). The module that loads the shipped solutions implements it from the files of the solutions; the module that
 * numbers records asks it through an optional bean and checks every series against the objects the platform defines
 * when the application starts, so the record store has no dependency on solutions (the same inversion as
 * {@link ShippedApplications}).
 *
 * <p>A declaration is data: it names a series, the field that holds its number and the formats to write it in. It holds
 * no code, and an organization cannot change it.
 */
public interface NumberSeriesDeclarations {

    /** Every series the shipped solutions declare, in a stable order. */
    List<NumberSeriesDeclaration> declared();
}
