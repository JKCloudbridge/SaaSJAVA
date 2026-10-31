# CAPA (key `capa`)

Decision: [ADR-0225](../adr/0225-the-order-of-applications-and-objects-and-policies-shared-between-solutions.md) (FA13A). The second tile. It has only its Home, which lists no tabs, and the tile says "Coming soon".

- **Plan feature:** `capa` (name CAPA), in no plan by default. Give it by a plan or an organization override in the console. Taking it away hides
  the tile and loses nothing, because it holds no data.
- **File:** `platform-app/src/main/resources/solutions/capa/solution.yml`: `order: 2`, one application, no objects, no access policies, no code.
- **To make a tab real:** change the entry in that file (give it a real page key and, if it shows records, an object that a bundle brings; remove
  `placeholder: true`) and register the page in the web application, as for the process applications ([processes.md](processes.md)).
