package app.platform.sharedkernel.solution;

import java.util.Set;

/**
 * The objects whose records may own a sheet, as the platform itself ships them (Functional track FA3, ADR-0186). The
 * module that loads the shipped solutions implements it from the files of the solutions; the module that keeps sheets
 * asks it through an optional bean, so it has no dependency on solutions (the same inversion as
 * {@link NumberSeriesDeclarations}).
 *
 * <p>Without it, any member who may change a record of any object could attach megabytes of grid to it. With it, a
 * sheet can belong only to a record of an object a shipped solution declares as a sheet owner. The declaration is
 * data: it holds no code, and an organization cannot change it.
 */
public interface SheetOwnerDeclarations {

    /** The API names of the objects whose records may own a sheet. */
    Set<String> owners();
}
