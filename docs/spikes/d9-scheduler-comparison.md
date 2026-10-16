# Decision D9: scheduler candidates, the evidence

Time-boxed comparison made on 2026-10-16 for [ADR-0134](../adr/0134-the-scheduler-of-timed-workflows-decision-d9.md). This is the
one place where the candidate libraries are named. It is evidence, not an endorsement: what was checked and what was not.

## Candidates

| Kind | Library | What it is |
|------|---------|------------|
| Job store with its own table | db-scheduler | A persistent scheduler for Java on a single table, with a Spring Boot starter for Spring Boot 4 |
| Cron with a JDBC job store | Quartz | The long-standing scheduler; Spring Boot configures it (`spring.quartz.job-store-type=jdbc`) with a set of tables it creates from its own scripts |
| Job queue with a dashboard | JobRunr | Background jobs with a dashboard; LGPL-3.0 with a commercial licence offered; a Spring Boot 4 starter exists; creates its own tables |

## Criteria and what was found

1. **Works under forced row level security and per-tenant isolation.** None of them sets a tenant on its connection or knows a
   tenant column. Their tables would have to be platform-level tables with no policy (which `SchemaConventions` allows only for a
   short, reviewed list) or tenant tables whose rows the library's own statements could not see. This was judged from how they
   are configured and from the public descriptions of their storage; it was not tried against the schema. **This is the deciding
   criterion.**
2. **Java 25 and Spring Boot 4.1.** Starters for Spring Boot 4 exist for db-scheduler and JobRunr (artifacts listed on Maven
   Central, db-scheduler's starter last released in May 2026); Spring Boot's own documentation covers Quartz for 4.1. **Java 25
   support was not confirmed from the sources found for any of them.**
3. **Several instances never run a job twice; a dead instance's job is taken over.** All three document this (row claims with
   heartbeats, or clustered mode). Not tested here.
4. **No lock-in or hosted component.** All three run in the application. JobRunr's dual licence and dashboard are extra surface.
5. **Dependency and vulnerability status.** Not scanned; each is one more dependency (Quartz and JobRunr bring the larger trees).
6. **Own tables fit the migration rules (ADR-0009).** Quartz and JobRunr create their schema from their own scripts (Spring Boot
   can initialize Quartz's at start, and its documentation warns that the bundled scripts drop existing tables); db-scheduler
   documents a table to create. Any of them would need the table copied into a numbered migration and kept in step with the
   library's version.

## Conclusion

No candidate passes criterion 1, and criteria 2 and 5 could not be closed from what was found. The plan allowed a scheduler of
our own on the claim-and-lease idiom of the outbox if that was the result, provided the product owner was told first. The product
owner confirmed it on 2026-10-16.

## Sources

- Maven Central listing of the db-scheduler artifacts, and its releases page on GitHub
- The Spring Boot reference documentation, "Quartz Scheduler" (4.0 and 4.1)
- The Maven Central entry and the Wikipedia entry of JobRunr (licence, Spring Boot 4 starter, supported databases)
