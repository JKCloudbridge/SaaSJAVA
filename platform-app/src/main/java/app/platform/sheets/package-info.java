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
 * <p>FA3 creates and changes drafts only. The moves of a version out of draft (submit, approve, publish, retire) arrive
 * with the approval sprints; the database already refuses every move that is not part of the lifecycle.
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
