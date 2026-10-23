/**
 * Sheets module: the grid document that belongs to a record, and its versions (Functional track FA3, ADR-0172,
 * ADR-0173, ADR-0174, ADR-0186). A sheet is a spreadsheet-like grid: named columns of five types, header rows, optional
 * named rows, merged cells, styles, and the cells that hold fixed text. It belongs to one record of one object (the
 * owner) and is kept as numbered versions, one row each, never changed once a version has left draft and never deleted.
 *
 * <p>This module is generic: it knows no business word. Which objects may own a sheet is declared by a shipped solution
 * through the shared-kernel contract {@code SheetOwnerDeclarations}; who may read or change a sheet is the owner
 * record's own answer from the decision API (never a second rule for who may see a record, ADR-0115). The grid is
 * checked by one schema file and a validator in a fixed order, the size before anything is parsed, and a typed model
 * is the only form the rest of the module sees. A library's format never reaches the database or the API.
 *
 * <p>The grid is text of the organization. It never reaches a log, an audit record, an event, a metric tag or an error
 * message: they carry identifiers, counts and sizes only.
 *
 * <p>FA3 created and changed drafts. FA4 adds the moves of a version out of draft (submit, the end of a review,
 * publish, unpublish, retire) as {@link app.platform.sheets.SheetLifecycle}, called by the module that owns the thing
 * a sheet belongs to, and the counted correction of an approver while a version is in review; the database refuses
 * every move that is not part of the lifecycle whoever asks (ADR-0188, ADR-0189).
 *
 * <p>FF1 adds the <strong>fill-ins</strong> of a sheet (ADR-0192): what a person types into a sheet that was published.
 * The cells of a fill-in are one canonical document in a table of this module (one row for each header record of the
 * caller's own object), pinned for ever to the version of the sheet that was in use when the fill-in was started,
 * with a
 * status the database guards and cells that change only while the fill-in is a draft or returned, or by a counted
 * correction. The module that owns the header record starts, sends and ends a fill-in through
 * {@link app.platform.sheets.FillLifecycle}; who may read or change the cells is the header record's own answer from
 * the decision API, asked in one place. The cells are checked in a fixed order against the pinned version: only input
 * cells take a value, each value fits the type of its column, and rows can be added only where the sheet allows it.
 *
 * <p>Other modules may use only the types in this package (its public API). Everything in sub-packages is internal.
 * Allowed outgoing dependencies are declared below and verified by the architecture tests.
 */
@ApplicationModule(
        displayName = "Sheets",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "security",
            "data"
        })
package app.platform.sheets;

import org.springframework.modulith.ApplicationModule;
