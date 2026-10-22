# The cost of shares to roles (FA2B, ADR-0185)

A quick, focused measure and not part of the storage benchmark: it times the share condition of one list page (51 records) in three
shapes on a reduced copy of `object_record` and `record_share` (the same indexes, no row level security: it adds the same tenant test to
every shape), with and without 6,000 shares to roles, and prints the plan of the new shape.

```powershell
# from platform/, against any PostgreSQL 18 that accepts a superuser connection (the local services' container is enough)
Get-Content benchmark/role-shares/measure-role-shares.sql | docker exec -i <postgres container> psql -U <user> -d postgres -f -
```

It makes the database `scratch_fa2b` and leaves it for you to look at; remove it with `drop database scratch_fa2b`. The numbers it gave
when the sprint was developed are in ADR-0185, section 2. No threshold of the storage benchmark depends on it.
