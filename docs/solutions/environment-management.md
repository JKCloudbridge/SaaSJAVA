# The Environment Management application (key `environment-management`)

Decision: [ADR-0226](../adr/0226-applications-of-one-kind-on-shared-code-with-their-own-objects.md) (FA13B). The sixth tile of the grid. The same
functions as the [Quality Management System](inspection.md) over **records of its own**: a template, a fill-in or a change request made here is never
listed in the other application, and the reverse. It is made of files only (`platform-app/src/main/resources/solutions/environment-management/`).

## For administrators

- **Switch it on:** the plan feature `environment-management` (console name: Environment Management), in no plan by default. A platform administrator
  adds it to a plan or gives one organization an override. Taking it away hides the application at once and deletes nothing.
- **Tabs:** Home, Document Control, Change requests, Record Control, Report, Management Review (announced only) and the Department group entry. There
  is no Internal Audit tab.
- **Access policies (new, with their own names):** Environment Author, Environment Approver, Environment Publisher, Environment Reader, Environment
  Filler, Environment Reviewer. Holders of the Quality Management System's policies get **no** access here; assign these to people or groups.
- **Starts empty.** The number patterns start as a copy of the other application's (Setup, Numbering lists the series `environment-document` with its
  own counters). No approval process is shipped: set up the processes for the template, the fill-in and the change-request objects of this application
  in Setup, Approvals, like any other.
- **Departments:** the department setup (which group belongs to which location and department) is the organization's and is shared with the other
  applications. Publishing a template here uses the same groups.

## For contributors

The three objects are `EnvironmentTemplate`, `EnvironmentFill` and `EnvironmentChangeRequest`; they play the parts `template`, `fill` and
`changeRequest` in `solution.yml`. Their fields are a copy of the Quality Management System's on purpose (`EnvironmentParityTest`). The code is shared:
see ADR-0226 and the recipe for another application of this kind in [metadata-guide.md](../metadata-guide.md) section 44.

Tests: `RecordSetsTest`, `SolutionLoaderTest`, `SolutionValidatorTest`, `EnvironmentParityTest`, `InspectionArchitectureTest` (no database);
`EnvironmentManagementIT` (the gate matrix and the separation of the two applications), `ThirdApplicationIT` (an application made only of files).
