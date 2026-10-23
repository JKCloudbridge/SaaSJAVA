/**
 * The Audit maintenance cluster's own module (Functional track FA4, ADR-0180, ADR-0188, ADR-0189, ADR-0190): the glue
 * between the generic parts of the platform that make a template's lifecycle. It holds the cluster's words and nothing
 * generic depends on it (an architecture test, {@code SolutionBoundaryTest}, keeps those words in the places listed
 * there).
 *
 * <p>What it does:
 * <ul>
 *   <li>the hook of the approval engine for a template ({@code ApprovalHooks}): on submit it checks the draft and can
 *   be numbered and moves the version to review; at the first approval it takes the number from the series and writes
 *   it; when the request ends it moves the version to approved, rejected or withdrawn;</li>
 *   <li>publishing a template to the people of a department (a read share of the header to the group an administrator
 *   keeps for the pair of location and department), taking it back, and retiring it;</li>
 *   <li>the master list of templates with the lifecycle of each, and the lifecycle of one template;</li>
 *   <li>fill-ins (FF1, ADR-0192): starting one on the version of a template that is in use (the header record is made
 *   as the platform for the person who starts), the list of fill-ins newest first without their cells, and the hook of
 *   the approval engine for a fill-in (the cells are checked and moved to review on submit; a rejection returns the
 *   fill-in to its filler).</li>
 * </ul>
 *
 * <p>Nothing here decides who may see a record (the record store and the decision API do, ADR-0115), writes a record
 * other than the number (through the engine's narrow writer), the retired mark and the header of a fill-in with its
 * status (through the platform's own write, in two places only), or reads another module's tables. The words of a
 * template, its number, a location, a department, a group and what a person typed into a fill-in never reach a log, an
 * audit record, an event or an error message: they carry identifiers and counts.
 *
 * <p>This module has no public API: it is a leaf. Allowed outgoing dependencies are declared below and verified by the
 * architecture tests. */
@ApplicationModule(
        displayName = "Audit maintenance",
        allowedDependencies = {
            "sharedkernel",
            "tenant",
            "security",
            "metadata",
            "data",
            "sheets"
        })
package app.platform.inspection;

import org.springframework.modulith.ApplicationModule;
