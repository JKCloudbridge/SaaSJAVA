# Management Core (key `management-core`)

Decision: [ADR-0225](../adr/0225-the-order-of-applications-and-objects-and-policies-shared-between-solutions.md) (FA13A). The fifth tile. Tabs: Home, KPI management, Management Review and Plantwise Review; the last three are announced and say they are being prepared.

- **Plan feature:** `management-core` (name Management Core), in no plan by default. Give it by a plan or an organization override in the console. Taking it away hides
  the tile and loses nothing, because it holds no data.
- **File:** `platform-app/src/main/resources/solutions/management-core/solution.yml`: `order: 5`, one application, no objects, no access policies, no code.
- **To make a tab real:** change the entry in that file (give it a real page key and, if it shows records, an object that a bundle brings; remove
  `placeholder: true`) and register the page in the web application, as for the process applications ([processes.md](processes.md)).
