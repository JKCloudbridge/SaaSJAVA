#!/bin/bash
# Runs INSIDE the benchmark container (started by run-benchmark.ps1). Builds the schema of the repository, the proof
# table, loads the generated data, builds the indexes and analyses. Usage: setup.sh <big_n> <case_n> <small_t> <small_n> <users> <tenants>
set -euo pipefail
big_n=$1; case_n=$2; small_t=$3; small_n=$4; users=$5; tenants=$6
psql_owner() { psql -X -q -U postgres -d bench -v ON_ERROR_STOP=1 "$@"; }
stamp() { echo "[$(date +%H:%M:%S)] $*"; }

stamp "schema: the migration tool's history table (stub), every migration of the repository, manual migration M001"
psql_owner -c "create table flyway_schema_history (installed_rank integer)"
for f in $(ls /bench/migration/V*.sql | sort); do
    psql_owner -1 -f "$f" > /dev/null
done
psql_owner -f /bench/manual/M001__create_application_role.sql > /dev/null
stamp "indexes of the record table: remember their definitions, drop them for the load"
psql_owner -c "create table bench_index_defs as select indexname, indexdef from pg_indexes where tablename = 'object_record' and indexname <> 'object_record_pkey'"
psql_owner <<'SQL'
do $body$ declare r record; begin
  for r in select indexname from bench_index_defs loop execute 'drop index ' || quote_ident(r.indexname); end loop;
end $body$;
SQL
stamp "helpers (association table, generator functions, bypass role)"
psql_owner -f /bench/sql/01-proof-schema.sql > /dev/null

stamp "load: large organization $big_n + $case_n records, $small_t small organizations x 2 objects x $small_n, $users users"
psql_owner -v big_n="$big_n" -v case_n="$case_n" -v small_t="$small_t" -v small_n="$small_n" -v users="$users" \
    -v tenants="$tenants" -f /bench/sql/10-load.sql | grep -E "^(Time|INSERT)" || true

stamp "indexes"
psql_owner <<'SQL'
do $body$ declare r record; begin
  for r in select indexdef from bench_index_defs order by indexname loop execute r.indexdef; end loop;
end $body$;
SQL
stamp "analyze"
psql_owner -c "vacuum (analyze)"
# Flush the dirty pages of the load now, so that a checkpoint does not land in the first scenario that follows.
psql_owner -c "checkpoint"

# The values the scenario scripts receive (pgbench -D).
echo "-D big_n=$big_n -D case_n=$case_n -D case_base=100000000 -D small_t=$small_t -D small_n=$small_n -D small_base=200000000 -D users=$users -D tenants=$tenants" > /bench/vars
stamp "setup done"
