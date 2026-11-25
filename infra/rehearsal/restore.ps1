<#
 A backup restored into a clean database, by script and TIMED (Sprint 33, ADR-0294). The rehearsal's database is a logical dump
 (the owner of the rehearsal's container may read every row, row level security or not). In the cloud the restore is a snapshot or
 a point in time, which the owner drills by hand (runbooks/database.md); the measurement of how long that takes and how much it
 loses belongs to Sprint 35 and is not claimed here. What this proves: the procedure works end to end, a restored database holds
 what the original held, and the application can start on it.
 Prints the times and the counts (no value of any record).
#>
. "$PSScriptRoot/lib.ps1"
Invoke-Seed
$owner = $script:Env.POSTGRES_USER
$database = $script:Env.POSTGRES_DB
$copy = "restore_$([guid]::NewGuid().ToString('N').Substring(0,8))"

function Get-Counts([string]$name) {
    $sql = "select (select count(*) from tenant), (select count(*) from audit_record), (select platform_schema_version()), (select count(*) from flyway_schema_history), (select count(*) from object_record)"
    $answer = & docker compose --project-directory $script:Here exec -T postgres psql -U $owner -d $name -v ON_ERROR_STOP=1 -tA -c $sql 2>&1
    return "$answer".Trim()
}

$before = Get-Counts $database
Say "Original: tenants|audit records|schema version|migrations|records = $before"

$began = Get-Date
& docker compose --project-directory $script:Here exec -T postgres pg_dump -U $owner -d $database -Fc -f /tmp/rehearsal.dump
if ($LASTEXITCODE -ne 0) { throw 'pg_dump failed.' }
$dumpSeconds = ((Get-Date) - $began).TotalSeconds
$size = (& docker compose --project-directory $script:Here exec -T postgres sh -c 'stat -c %s /tmp/rehearsal.dump').Trim()

Get-OwnerSql "create database $copy" | Out-Null
$began = Get-Date
& docker compose --project-directory $script:Here exec -T postgres pg_restore -U $owner -d $copy --no-owner -e /tmp/rehearsal.dump
$restoreExit = $LASTEXITCODE
$restoreSeconds = ((Get-Date) - $began).TotalSeconds

$after = Get-Counts $copy
Say "Restored: tenants|audit records|schema version|migrations|records = $after"
Say ("Dump {0:N1} s, {1:N0} bytes; restore {2:N1} s" -f $dumpSeconds, [int64]$size, $restoreSeconds)
Check ($restoreExit -eq 0) 'the restore ended without an error'
Check ($before -eq $after) 'the restored database holds the same counts as the original'

# The application starts on the restored database (it only checks the schema; the migration job is not needed again).
$arguments = Get-ApiEnv @{ PLATFORM_DB_URL = "jdbc:postgresql://postgres:5432/$copy"; PLATFORM_HTTP_PORT = '8092'; PLATFORM_MANAGEMENT_PORT = '8093' }
Get-OwnerSql "grant connect on database $copy to platform_app" | Out-Null
Get-OwnerSql "select 1" | Out-Null
& docker compose --project-directory $script:Here exec -T postgres psql -U $owner -d $copy -v ON_ERROR_STOP=1 -q -c "grant usage on schema public to platform_app; grant select, insert, update, delete on all tables in schema public to platform_app" | Out-Null
Invoke-Docker run -d --name rehearsal-restored --network $script:Network @arguments platform-app:local | Out-Null
try {
    $ready = $false
    $until = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $until -and -not $ready) {
        if ("$(Invoke-DockerQuiet exec rehearsal-restored wget -q -O- http://127.0.0.1:8093/actuator/health/readiness)" -match '"status":"UP"') { $ready = $true } else { Start-Sleep -Seconds 2 }
    }
    Check $ready 'the application starts on the restored database and is ready'
} finally {
    Invoke-DockerQuiet rm -f rehearsal-restored | Out-Null
    Get-OwnerSql "drop database $copy" | Out-Null
    & docker compose --project-directory $script:Here exec -T postgres rm -f /tmp/rehearsal.dump | Out-Null
}
Finish 'backup and restore into a clean database'
