<#
.SYNOPSIS
    Gate G1 storage benchmark (ADR-0082): builds a PostgreSQL container, loads generated data, runs every scenario
    and writes a summary and a raw file. Outside the Maven build and outside CI on purpose.

.DESCRIPTION
    What it does, in order: starts a container of the production major version with the settings below; applies the
    repository's migrations V001 to V035 and the manual role migration M001; adds the proof table (sql\01-proof-schema.sql);
    generates the data inside the database; builds the indexes; runs the scenarios with pgbench (it ships inside the
    container, so nothing is installed on this machine); judges the numbers against the thresholds of ADR-0082 exactly
    as written; writes results\<time>-<size>\summary.md, raw.csv, environment.txt and raw\*.txt; removes the container
    and its data (unless -KeepContainer).

.EXAMPLE
    .\run-benchmark.ps1 -Size smoke        # a few minutes, tiny data: proves the harness works. Not a measurement.
.EXAMPLE
    .\run-benchmark.ps1                    # the full run: 2 million records in the large object
.EXAMPLE
    .\run-benchmark.ps1 -CleanupOnly       # remove the benchmark container and its data
#>
[CmdletBinding()]
param(
    [ValidateSet('smoke', 'default', 'large')][string]$Size = 'default',
    [int]$BigRecords, [int]$CaseRecords, [int]$SmallTenants, [int]$SmallRecords, [int]$Users, [int]$Tenants,
    [int]$Seconds, [int]$WarmupSeconds, [int]$UpdateMinutes,
    [int]$Clients = 8,
    [string]$Memory = '6g',
    [int]$Cpus = 6,
    [string]$Image = 'postgres:18',
    [switch]$KeepContainer,
    [switch]$SetupOnly,
    [switch]$CleanupOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$inv = [Globalization.CultureInfo]::InvariantCulture

$Container = 'platform-bench-pg'
$Volume = 'platform-bench-data'
$Root = $PSScriptRoot
$Repo = Split-Path $Root -Parent

function Remove-BenchContainer {
    & docker rm -f $Container 2>$null | Out-Null
    & docker volume rm -f $Volume 2>$null | Out-Null
}
if ($CleanupOnly) {
    Remove-BenchContainer
    Write-Host 'Benchmark container and data removed.'
    return
}

# ---- sizes -----------------------------------------------------------------------------------------------------
$presets = @{
    smoke   = @{ Big = 20000;    Case = 10000;   SmallT = 10;  SmallN = 200;  Users = 1000;   Tenants = 50;
                 Secs = 5;  Warm = 2; UpdMin = 1 }
    default = @{ Big = 2000000;  Case = 1000000; SmallT = 100; SmallN = 2000; Users = 100000; Tenants = 2000;
                 Secs = 30; Warm = 5; UpdMin = 10 }
    large   = @{ Big = 10000000; Case = 1000000; SmallT = 100; SmallN = 2000; Users = 100000; Tenants = 2000;
                 Secs = 30; Warm = 5; UpdMin = 10 }
}
$p = $presets[$Size]
function Pick($given, $name, $preset) { if ($PSBoundParameters.ContainsKey($name)) { return $given } else { return $preset } }
$BigRecords = Pick $BigRecords 'BigRecords' $p.Big
$CaseRecords = Pick $CaseRecords 'CaseRecords' $p.Case
$SmallTenants = Pick $SmallTenants 'SmallTenants' $p.SmallT
$SmallRecords = Pick $SmallRecords 'SmallRecords' $p.SmallN
$Users = Pick $Users 'Users' $p.Users
$Tenants = Pick $Tenants 'Tenants' $p.Tenants
$Seconds = Pick $Seconds 'Seconds' $p.Secs
$WarmupSeconds = Pick $WarmupSeconds 'WarmupSeconds' $p.Warm
$UpdateMinutes = Pick $UpdateMinutes 'UpdateMinutes' $p.UpdMin
if ($Tenants -lt $SmallTenants + 1) { $Tenants = $SmallTenants + 1 }
if ($BigRecords -lt 1000) { throw 'BigRecords must be at least 1000 (a bulk batch has 1000 rows).' }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$ResultDir = Join-Path $Root "results\$stamp-$Size"
$RawDir = Join-Path $ResultDir 'raw'
New-Item -ItemType Directory -Force -Path $RawDir | Out-Null

# ---- helpers ---------------------------------------------------------------------------------------------------
function Say($text) { Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) }

function Sql([string]$text, [string]$user = 'postgres') {
    $out = & docker exec $Container psql -X -U $user -d bench -At -F '|' -c $text
    if ($LASTEXITCODE -ne 0) { throw "SQL failed: $text" }
    return $out
}

function Start-Bench([string]$Name, [string]$Script, [int]$ClientCount, [int]$Secs, [string]$User = 'platform_app',
                     [int]$Warm = -1, [string]$PgOptions = '', [switch]$Thirds, [int]$Rate = 0) {
    if ($Warm -lt 0) { $Warm = $WarmupSeconds }
    $dockerArgs = @('exec')
    if ($PgOptions) { $dockerArgs += @('-e', "`"PGOPTIONS=$PgOptions`"") }
    if ($Thirds) { $dockerArgs += @('-e', 'THIRDS=1') }
    $dockerArgs += @($Container, 'bash', '/bench/run.sh', $Name, "/bench/scenarios/$Script", $ClientCount, $Secs, $User, $Warm, $Rate)
    $outFile = Join-Path $RawDir "$Name.txt"
    $proc = Start-Process -FilePath 'docker' -ArgumentList $dockerArgs -NoNewWindow -PassThru `
        -RedirectStandardOutput $outFile -RedirectStandardError "$outFile.err"
    $null = $proc.Handle
    return [pscustomobject]@{ Name = $Name; Script = $Script; Clients = $ClientCount; User = $User; Process = $proc; File = $outFile }
}

$script:Results = New-Object System.Collections.Generic.List[object]

function Wait-Bench($run) {
    $run.Process.WaitForExit()
    $text = Get-Content -Raw -Path $run.File
    $row = [pscustomobject]@{
        Id = $run.Name; Script = $run.Script; Clients = $run.Clients; User = $run.User
        Count = 0; Tps = 0.0; P50 = 0.0; P95 = 0.0; P99 = 0.0; Max = 0.0; Failed = 0; Thirds = @()
    }
    if ($text -match 'RESULT (\d+) (\d+) (\d+) (\d+) (\d+)') {
        $row.Count = [int64]$Matches[1]
        $row.P50 = [double]$Matches[2] / 1000.0
        $row.P95 = [double]$Matches[3] / 1000.0
        $row.P99 = [double]$Matches[4] / 1000.0
        $row.Max = [double]$Matches[5] / 1000.0
    }
    if ($text -match 'tps = ([0-9.]+)') { $row.Tps = [double]::Parse($Matches[1], $inv) }
    if ($text -match 'number of failed transactions: (\d+)') { $row.Failed = [int]$Matches[1] }
    # PostgreSQL 18's pgbench has no way to continue after an error: a client whose statement fails (for example by
    # the statement timeout) stops, and the report says so. Every such client counts as a failure.
    $row.Failed += ([regex]::Matches($text, 'aborted in command')).Count
    foreach ($m in [regex]::Matches($text, 'THIRD (\d) (\d+) (\d+)')) {
        $row.Thirds += [double]$m.Groups[3].Value / 1000.0
    }
    if ($row.Count -eq 0) {
        $err = ''
        if (Test-Path "$($run.File).err") { $err = Get-Content -Raw "$($run.File).err" }
        throw "Scenario $($run.Name) produced no transactions. Output: $($text.Substring(0, [Math]::Min(600, $text.Length))) $err"
    }
    $script:Results.Add($row)
    Say ("  {0,-22} clients={1,-3} n={2,-8} tps={3,-9} p50={4,-8} p95={5,-8} p99={6,-8} failed={7}" -f `
        $row.Id, $row.Clients, $row.Count, $row.Tps.ToString('0.0', $inv), $row.P50.ToString('0.00', $inv),
        $row.P95.ToString('0.00', $inv), $row.P99.ToString('0.00', $inv), $row.Failed)
    return $row
}

function Run-Bench { Wait-Bench (Start-Bench @args) }
function Get-Row([string]$id) { return $script:Results | Where-Object { $_.Id -eq $id } | Select-Object -First 1 }
function Median([double[]]$values) {
    $s = $values | Sort-Object
    return $s[[int][Math]::Floor(($s.Count - 1) / 2)]
}

$script:Verdicts = New-Object System.Collections.Generic.List[object]
function Judge([string]$id, [string]$what, $measured, [string]$unit, $limit, [string]$rule = 'le') {
    # rule 'le': pass when measured <= limit; 'ge': pass when measured >= limit; 'zero': pass when measured = 0
    $result = 'NOT MEASURED'
    if ($null -ne $measured) {
        switch ($rule) {
            'le'    { if ($measured -le $limit) { $result = 'PASS' } else { $result = 'FAIL' } }
            'ge'    { if ($measured -ge $limit) { $result = 'PASS' } else { $result = 'FAIL' } }
            'zero'  { if ($measured -eq 0) { $result = 'PASS' } else { $result = 'FAIL' } }
            default { throw "unknown rule $rule" }
        }
    }
    $script:Verdicts.Add([pscustomobject]@{ Id = $id; What = $what; Measured = $measured; Unit = $unit; Limit = $limit; Rule = $rule; Result = $result })
}

$script:Notes = New-Object System.Collections.Generic.List[string]
$script:Info = New-Object System.Collections.Generic.List[string]
function Phase([string]$name, [scriptblock]$body) {
    Say "== $name"
    try { & $body } catch {
        $msg = $_.Exception.Message
        Say "  PHASE FAILED: $msg"
        $script:Notes.Add("PHASE FAILED ($name): $msg")
    }
}

# ---- container -------------------------------------------------------------------------------------------------
Say "Result folder: $ResultDir"
& docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker is not running. Start Docker Desktop and try again.' }
Remove-BenchContainer
$pgSettings = @(
    'shared_buffers=2GB', 'effective_cache_size=4GB', 'work_mem=16MB', 'maintenance_work_mem=512MB',
    'max_wal_size=8GB', 'min_wal_size=1GB', 'checkpoint_timeout=15min', 'random_page_cost=1.1',
    'effective_io_concurrency=200', 'max_connections=200', 'max_parallel_workers_per_gather=2', 'jit=off',
    'track_io_timing=on'
)
$runArgs = @('run', '-d', '--name', $Container, '--memory', $Memory, '--cpus', "$Cpus", '--shm-size', '1g',
    '-e', 'POSTGRES_PASSWORD=bench', '-e', 'POSTGRES_DB=bench', '-v', "${Volume}:/var/lib/postgresql", $Image)
foreach ($s in $pgSettings) { $runArgs += @('-c', $s) }
Say "Starting $Image (memory $Memory, $Cpus processors)"
& docker @runArgs | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not start the container.' }
$ready = $false
for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 2
    & docker exec $Container pg_isready -U postgres -d bench -q 2>$null
    if ($LASTEXITCODE -eq 0) {
        Start-Sleep -Seconds 3   # the image restarts the server once after creating the database
        & docker exec $Container pg_isready -U postgres -d bench -q 2>$null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    }
}
if (-not $ready) { throw 'PostgreSQL did not become ready.' }

& docker exec $Container mkdir -p /bench /tmp/bench | Out-Null
& docker cp (Join-Path $Root 'sql') "${Container}:/bench/sql" | Out-Null
& docker cp (Join-Path $Root 'scenarios') "${Container}:/bench/scenarios" | Out-Null
& docker cp (Join-Path $Root 'container/setup.sh') "${Container}:/bench/setup.sh" | Out-Null
& docker cp (Join-Path $Root 'container/run.sh') "${Container}:/bench/run.sh" | Out-Null
& docker cp (Join-Path $Repo 'platform-app/src/main/resources/db/migration') "${Container}:/bench/migration" | Out-Null
& docker cp (Join-Path $Repo 'db/manual') "${Container}:/bench/manual" | Out-Null
# The repository is LF, but a checkout on Windows may have converted the files: make them LF inside the container.
& docker exec $Container bash -c "find /bench -type f \( -name '*.sh' -o -name '*.sql' \) -exec sed -i 's/\r$//' {} +"

# ---- environment -----------------------------------------------------------------------------------------------
$env = New-Object System.Collections.Generic.List[string]
$env.Add("Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$env.Add("Size preset: $Size")
$env.Add("Data: large organization $BigRecords + $CaseRecords records; $SmallTenants small organizations x 2 objects x $SmallRecords; $Users users; $Tenants organizations")
$env.Add("Timing: $Seconds s per scenario, $WarmupSeconds s warm-up, $Clients clients, update-heavy $UpdateMinutes min")
$env.Add("Container: image $Image, memory limit $Memory, processor limit $Cpus, shared memory 1g")
$env.Add("PostgreSQL settings: " + ($pgSettings -join ', '))
$env.Add("Docker: " + ((& docker version --format 'client {{.Client.Version}}, server {{.Server.Version}}') -join ' '))
$env.Add("Docker VM: " + ((& docker info --format '{{.NCPU}} processors, {{.MemTotal}} bytes of memory') -join ' '))
try {
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $mem = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
    $env.Add(("Host: {0} physical cores, {1} logical processors, {2:N1} GB memory" -f $cpu.NumberOfCores, $cpu.NumberOfLogicalProcessors, ($mem / 1GB)))
    $disk = Get-PhysicalDisk | Select-Object -First 1
    $env.Add("Host disk type: $($disk.MediaType)")
} catch { $env.Add('Host details: not available') }
$env.Add('Latency: measured at the database server through a local socket inside the container; no network time is included.')

# ---- setup -----------------------------------------------------------------------------------------------------
$setupStart = Get-Date
Say "Building the schema and loading the data (this is the slow part of a full run)"
& docker exec $Container bash /bench/setup.sh $BigRecords $CaseRecords $SmallTenants $SmallRecords $Users $Tenants
if ($LASTEXITCODE -ne 0) { throw 'Setup failed; see the output above.' }
$setupMinutes = ((Get-Date) - $setupStart).TotalMinutes
$env.Add(("Setup (schema, load, indexes): {0:N1} minutes" -f $setupMinutes))
$env.Add('Server: ' + (Sql 'select version()'))
$env.Add('Locale: ' + (Sql 'select datcollate from pg_database where datname = current_database()'))

$sizes = Sql "select relname, pg_size_pretty(pg_total_relation_size(oid)), pg_size_pretty(pg_table_size(oid)), pg_size_pretty(pg_indexes_size(oid)), reltuples::bigint from pg_class where relname in ('object_record', 'object_record_link', 'tenant', 'platform_user', 'oauth2_authorization', 'membership') order by pg_total_relation_size(oid) desc"
$indexSizes = Sql "select indexrelid::regclass, pg_size_pretty(pg_relation_size(indexrelid)) from pg_index where indrelid = 'object_record'::regclass order by pg_relation_size(indexrelid) desc"
$dbSize = Sql 'select pg_size_pretty(pg_database_size(current_database()))'
$rowsByObject = Sql "select object_api_name, count(*) from object_record group by 1 order by 2 desc limit 5"

if ($SetupOnly) { Say "Set-up only: container $Container kept with the loaded data (remove with -CleanupOnly)."; return }

# ---- phases ----------------------------------------------------------------------------------------------------
Phase 'S1 S2 S3 S5 S6 S10: reads, updates, filters, sorting, joins (8 clients each)' {
    foreach ($s in @('s01-read-by-id', 's02-update-by-id', 's03a-filter-text', 's03b-filter-unique-text', 's03c-filter-number',
                     's03d-filter-date', 's03e-filter-lookup', 's05-sort-keyset', 's06a-and', 's06b-or',
                     's10a-join-forward', 's10b-join-reverse', 's04a-nonindexed-common', 's04b-nonindexed-rare')) {
        $null = Run-Bench $s "$s.sql" $Clients $Seconds
    }
}

Phase 'S4 pool test: the whole-object scan at 16 clients with a read beside it' {
    $scan = Start-Bench 's04b-16clients' 's04b-nonindexed-rare.sql' 16 $Seconds -Warm 0 -PgOptions '-c statement_timeout=5000'
    Start-Sleep -Seconds 2
    $probe = Start-Bench 's04b-probe-read' 's01-read-by-id.sql' 2 ([Math]::Max(2, $Seconds - 4)) -Warm 0
    $null = Wait-Bench $scan
    $null = Wait-Bench $probe
}

Phase 'Diagnostic: filters written against the JSON payload (not judged)' {
    # Why the typed copies of indexed fields exist (ADR-0082, amendment 1): with row level security PostgreSQL does not use
    # an index on the JSON field operator or on the containment operator, because neither is leakproof.
    Sql "create index object_record_x_department on object_record (tenant_id, object_api_name, (payload ->> 'Department__c'), id) where deleted_at is null" | Out-Null
    Sql 'create index object_record_x_gin on object_record using gin (payload jsonb_path_ops)' | Out-Null
    Sql 'analyze object_record' | Out-Null
    try {
        $secs = [Math]::Min($Seconds, 15)
        $xa = Run-Bench 'x01-expression-app' 'x01-expression-filter.sql' $Clients $secs 'platform_app'
        $xb = Run-Bench 'x01-expression-bypass' 'x01-expression-filter.sql' $Clients $secs 'bench_bypass'
        $ga = Run-Bench 'x02-gin-app' 'x02-gin-filter.sql' $Clients $secs 'platform_app'
        $gb = Run-Bench 'x02-gin-bypass' 'x02-gin-filter.sql' $Clients $secs 'bench_bypass'
        $script:Info.Add(("Diagnostic: a filter on a JSON field with an index on that expression: p95 {0} ms under row level security, {1} ms without it. A filter by containment with a GIN index: p95 {2} ms with, {3} ms without. (The judged scenarios use typed copies of the indexed fields.)" -f `
            $xa.P95.ToString('0.00', $inv), $xb.P95.ToString('0.00', $inv), $ga.P95.ToString('0.00', $inv), $gb.P95.ToString('0.00', $inv)))
        $plans = @"
begin;
select set_config('app.current_tenant', bench_tenant(1)::text, true);
explain (analyze, costs off, timing off, summary on) select id from object_record where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null and (payload ->> 'Department__c') = 'Dept 07' order by id limit 50;
explain (analyze, costs off, timing off, summary on) select id from object_record where tenant_id = bench_tenant(1) and object_api_name = 'Employee__c' and deleted_at is null and s_text_1 = 'Dept 07' order by id limit 50;
rollback;
"@
        $out = $plans | & docker exec -i $Container psql -X -U platform_app -d bench
        $out2 = $plans.Replace("select set_config('app.current_tenant', bench_tenant(1)::text, true);", '') | & docker exec -i $Container psql -X -U bench_bypass -d bench
        Set-Content -Path (Join-Path $RawDir 'diagnostic-plans.txt') -Value (@('--- application role (row level security on) ---') + $out + @('--- bypass role (row level security off) ---') + $out2) -Encoding UTF8
    }
    finally {
        Sql 'drop index if exists object_record_x_department' | Out-Null
        Sql 'drop index if exists object_record_x_gin' | Out-Null
    }
}

Phase 'S7 noisy neighbour' {
    # Judged: the large organization works hard but at a stated rate (the load of a busy organization): 400 filters a
    # second and one whole-object scan a second. Reported only: the same sessions running flat out (ADR-0082, amendment 2).
    $alone = Run-Bench 's07-alone' 's07-small-mix.sql' $Clients $Seconds
    $bgSeconds = $Seconds + $WarmupSeconds + 10
    $bg1 = Start-Bench 's07-bg-indexed' 's03a-filter-text.sql' 8 $bgSeconds -Warm 0 -Rate 400
    $bg2 = Start-Bench 's07-bg-scan' 's04b-nonindexed-rare.sql' 4 $bgSeconds -Warm 0 -Rate 1
    Start-Sleep -Seconds 5
    $null = Run-Bench 's07-loaded' 's07-small-mix.sql' $Clients $Seconds
    $null = Wait-Bench $bg1
    $null = Wait-Bench $bg2
    $bg3 = Start-Bench 's07-bg-indexed-flatout' 's03a-filter-text.sql' 8 $bgSeconds -Warm 0
    $bg4 = Start-Bench 's07-bg-scan-flatout' 's04b-nonindexed-rare.sql' 4 $bgSeconds -Warm 0
    Start-Sleep -Seconds 5
    $flat = Run-Bench 's07-loaded-flatout' 's07-small-mix.sql' $Clients $Seconds
    $null = Wait-Bench $bg3
    $null = Wait-Bench $bg4
    if ($alone.P95 -gt 0) {
        $script:Info.Add(("S7 reported, not judged: with the large organization's sessions running flat out (12 sessions that never rest, on {0} processors) the small organizations' p95 was {1} times their p95 alone." -f `
            $Cpus, ([Math]::Round($flat.P95 / $alone.P95, 2)).ToString('0.00', $inv)))
    }
}

Phase 'S11 row level security on and off (three alternating pairs)' {
    foreach ($pair in 1..3) {
        foreach ($s in @(@('s01', 's01-read-by-id.sql'), @('s03a', 's03a-filter-text.sql'), @('s04b', 's04b-nonindexed-rare.sql'))) {
            $null = Run-Bench "s11-$($s[0])-on-$pair" $s[1] $Clients $Seconds 'platform_app'
            $null = Run-Bench "s11-$($s[0])-off-$pair" $s[1] $Clients $Seconds 'bench_bypass'
        }
    }
}

$hotBefore = $null
Phase 'S8 concurrent updates of the same records' {
    $q = "select coalesce(sum(version), 0), coalesce(sum((payload ->> 'Counter__c')::integer), 0) from object_record where id in (select bench_id(g) from generate_series(1, 10) g)"
    $before = (Sql $q).Split('|')
    $row = Run-Bench 's08-hot-update' 's08-hot-update.sql' 16 $Seconds
    $after = (Sql $q).Split('|')
    $dv = [int64]$after[0] - [int64]$before[0]
    $dc = [int64]$after[1] - [int64]$before[1]
    $lost = $dv - $dc
    $conflicts = $row.Count - $dv
    $script:Info.Add("S8: $($row.Count) attempts (warm-up excluded), the version rose by $dv, the counter by $dc, conflicts answered: $conflicts (a conflict is the version check refusing a stale update).")
    Judge 'S8' 'Lost updates (version rise minus counter rise); must be 0' $lost 'updates' 0 'zero'
}

Phase 'S9 bulk insert and upsert (batches of 1,000)' {
    $ins = Run-Bench 's09a-bulk-insert' 's09a-bulk-insert.sql' 1 $Seconds
    $ups = Run-Bench 's09b-bulk-upsert' 's09b-bulk-upsert.sql' 1 $Seconds
    $null = Run-Bench 's09a-bulk-insert-4workers' 's09a-bulk-insert.sql' 4 $Seconds
    Judge 'S9a' 'Bulk insert, records per second, one worker' ([Math]::Round($ins.Tps * 1000)) 'records/s' 5000 'ge'
    Judge 'S9b' 'Bulk upsert, records per second, one worker' ([Math]::Round($ups.Tps * 1000)) 'records/s' 5000 'ge'
    Sql "delete from object_record where object_api_name = 'Import__c'" | Out-Null
}

Phase 'Carried notes: round trips, host lookup, authentication, membership (one client: the cost of one request, without queueing)' {
    $null = Run-Bench 'c0a-read-bare' 'c0a-read-bare.sql' 1 $Seconds 'bench_bypass'
    $null = Run-Bench 'c0b-read-tenant-only' 'c0b-read-tenant-only.sql' 1 $Seconds 'bench_bypass'
    $null = Run-Bench 'c0c-read-both-settings' 's01-read-by-id.sql' 1 $Seconds 'bench_bypass'
    $null = Run-Bench 'c1-host-lookup' 'c1-host-lookup.sql' 1 $Seconds
    $null = Run-Bench 'c2-authentication' 'c2-authentication.sql' 1 $Seconds
    $null = Run-Bench 'c3-membership' 'c3-membership.sql' 1 $Seconds
}

$s12 = $null
Phase 'S12 storage, bloat and vacuum under an update-heavy load' {
    Sql 'vacuum (analyze) object_record' | Out-Null
    $stat = "select pg_table_size('object_record'), pg_indexes_size('object_record'), n_live_tup, n_dead_tup, n_tup_upd, n_tup_hot_upd, autovacuum_count, vacuum_count from pg_stat_user_tables where relname = 'object_record'"
    $b = (Sql $stat).Split('|')
    $beforeTotal = [int64]$b[0] + [int64]$b[1]
    $secs = $UpdateMinutes * 60
    $row = Wait-Bench (Start-Bench 's12-update-heavy' 's12-update-heavy.sql' $Clients $secs -Thirds)
    $a = (Sql $stat).Split('|')
    $afterRunTotal = [int64]$a[0] + [int64]$a[1]
    $manual = $false
    for ($i = 0; $i -lt 36; $i++) {   # up to 3 minutes for autovacuum to catch up
        $c = (Sql $stat).Split('|')
        if ([int64]$c[3] -le 0.05 * [Math]::Max(1, [int64]$c[2])) { break }
        Start-Sleep -Seconds 5
    }
    if ([int64]$c[3] -gt 0.05 * [Math]::Max(1, [int64]$c[2]) -and [int64]$c[6] -le [int64]$b[6]) {
        $manual = $true
        Sql 'vacuum (analyze) object_record' | Out-Null
        $c = (Sql $stat).Split('|')
    }
    $settledTotal = [int64]$c[0] + [int64]$c[1]
    $upd = [int64]$c[4] - [int64]$b[4]
    $hot = [int64]$c[5] - [int64]$b[5]
    $hotShare = 0.0
    if ($upd -gt 0) { $hotShare = 100.0 * $hot / $upd }
    $growth = 100.0 * ($settledTotal - $beforeTotal) / $beforeTotal
    $growthRun = 100.0 * ($afterRunTotal - $beforeTotal) / $beforeTotal
    $perRecord = $null
    $live = [int64]$c[2]
    if ($live -gt 0) { $perRecord = [Math]::Round($settledTotal / $live) }
    $info = "S12: $upd updates; heap-only updates: $hot ($($hotShare.ToString('0.0', $inv)) percent); table and indexes grew $($growthRun.ToString('0.0', $inv)) percent during the run and $($growth.ToString('0.0', $inv)) percent once vacuum had caught up"
    if ($manual) { $info += ' (autovacuum had not caught up within 3 minutes: the harness ran a manual vacuum)' } else { $info += ' (autovacuum, started by the server)' }
    $info += "; autovacuum runs during the run: $([int64]$c[6] - [int64]$b[6]); bytes per live record (table + indexes): $perRecord"
    $script:Info.Add($info)
    Judge 'S12a' 'Growth of table and indexes after vacuum (percent)' ([Math]::Round($growth, 1)) 'percent' 30 'le'
    if ($row.Thirds.Count -eq 3 -and $row.Thirds[0] -gt 0) {
        $ratio = [Math]::Round($row.Thirds[2] / $row.Thirds[0], 2)
        $script:Info.Add("S12: p95 of the first, middle and last third of the run: $($row.Thirds[0].ToString('0.00', $inv)), $($row.Thirds[1].ToString('0.00', $inv)), $($row.Thirds[2].ToString('0.00', $inv)) ms")
        Judge 'S12b' 'p95 of the last third divided by the first third' $ratio 'x' 1.25 'le'
    }
    try {
        $idx = Sql "select indexrelid::regclass, round((pgstatindex(indexrelid)).avg_leaf_density::numeric, 1) from pg_index where indrelid = 'object_record'::regclass order by 1"
        $script:Info.Add('Leaf density of the indexes after the run (a fresh index is about 90): ' + ($idx -join '; '))
    } catch { }
}

# ---- judgement -------------------------------------------------------------------------------------------------
function P95([string]$id) { $r = Get-Row $id; if ($null -eq $r) { return $null } else { return [Math]::Round($r.P95, 2) } }
Judge 'S1' 'Read one record by id, p95' (P95 's01-read-by-id') 'ms' 20
Judge 'S2' 'Update one record by id with its version, p95' (P95 's02-update-by-id') 'ms' 40
foreach ($pair in @(@('s03a-filter-text', 'text'), @('s03b-filter-unique-text', 'unique text'), @('s03c-filter-number', 'number'),
                    @('s03d-filter-date', 'date'), @('s03e-filter-lookup', 'lookup'))) {
    Judge ('S3 ' + $pair[1]) ("Indexed filter ($($pair[1])), sorted, one page, p95") (P95 $pair[0]) 'ms' 150
}
Judge 'S4b' 'Non-indexed filter, whole object scanned, 8 clients, p95' (P95 's04b-nonindexed-rare') 'ms' 1000
$scan16 = Get-Row 's04b-16clients'
if ($null -ne $scan16) { Judge 'S4 pool' 'Whole-object scans at 16 clients: transactions that failed or timed out' $scan16.Failed 'transactions' 0 'zero' } else { Judge 'S4 pool' 'Whole-object scans at 16 clients: failures' $null 'transactions' 0 'zero' }
Judge 'S4 probe' 'A read (S1) beside the 16 scans, p95' (P95 's04b-probe-read') 'ms' 100
Judge 'S5' 'Whole object sorted by an indexed field, keyset page, p95' (P95 's05-sort-keyset') 'ms' 150
Judge 'S6 AND' 'Two indexed fields with AND, p95' (P95 's06a-and') 'ms' 150
Judge 'S6 OR' 'Two indexed fields with OR, p95' (P95 's06b-or') 'ms' 150
$alone = Get-Row 's07-alone'; $loaded = Get-Row 's07-loaded'
if ($alone -and $loaded -and $alone.P95 -gt 0) { Judge 'S7' 'Small organizations: p95 with the large organization under load divided by p95 alone' ([Math]::Round($loaded.P95 / $alone.P95, 2)) 'x' 1.25 'le' } else { Judge 'S7' 'Small organizations under load vs alone' $null 'x' 1.25 'le' }
Judge 'S10 forward' 'Many-to-many join, forward, p95' (P95 's10a-join-forward') 'ms' 150
Judge 'S10 reverse' 'Many-to-many join, reverse, p95' (P95 's10b-join-reverse') 'ms' 150
foreach ($s in @('s01', 's03a', 's04b')) {
    $on = @(); $off = @()
    foreach ($pair in 1..3) {
        $a = Get-Row "s11-$s-on-$pair"; $b = Get-Row "s11-$s-off-$pair"
        if ($a -and $b) { $on += $a.P95; $off += $b.P95 }
    }
    if ($on.Count -eq 3 -and (Median $off) -gt 0) {
        Judge ("S11 $s") ("Row level security cost, scenario ${s}: median p95 on divided by off") ([Math]::Round((Median $on) / (Median $off), 3)) 'x' 1.10 'le'
    } else { Judge ("S11 $s") "Row level security cost, scenario $s" $null 'x' 1.10 'le' }
}

# carried notes
$carried = New-Object System.Collections.Generic.List[string]
$c0a = Get-Row 'c0a-read-bare'; $c0b = Get-Row 'c0b-read-tenant-only'; $c0c = Get-Row 'c0c-read-both-settings'
$c1 = Get-Row 'c1-host-lookup'; $c2 = Get-Row 'c2-authentication'; $c3 = Get-Row 'c3-membership'
if ($c0a -and $c0b -and $c0c -and $c1 -and $c2 -and $c3) {
    $net = 0.5
    $rt = [Math]::Max(0.0, $c0c.P95 - $c0a.P95)
    $member = [Math]::Max(0.0, $c3.P95 - $rt)
    $items = @(
        @{ Name = 'two statements that start every transaction (application name, tenant)'; P95 = $rt; Statements = 2 },
        @{ Name = 'lookup of the organization by host name'; P95 = $c1.P95; Statements = 1 },
        @{ Name = 'check of the sign-in grant (grant joined with user status and security version)'; P95 = $c2.P95; Statements = 2 },
        @{ Name = 'membership check and security version read (without the two statements above)'; P95 = $member; Statements = 2 }
    )
    $total = 0.0
    foreach ($i in $items) { $i.Adjusted = $i.P95 + $net * $i.Statements; $total += $i.Adjusted }
    $carried.Add("Per-request database time that every request of an organization host pays before it does any work, p95 measured plus an assumed $net ms per round trip: $($total.ToString('0.00', $inv)) ms.")
    foreach ($i in $items) {
        $share = 0.0
        if ($total -gt 0) { $share = 100.0 * $i.Adjusted / $total }
        $recommend = 'no cache'
        if ($total -ge 5.0 -and $i.P95 -ge 1.0 -and $share -ge 25.0) { $recommend = 'a cache with a stated bound is worth considering' }
        $carried.Add(("- {0}: p95 {1} ms measured, {2} ms with the round trips, {3} percent of the total: **{4}**" -f `
            $i.Name, $i.P95.ToString('0.00', $inv), $i.Adjusted.ToString('0.00', $inv), $share.ToString('0', $inv), $recommend))
    }
    $carried.Add("Decision rule (ADR-0082): a cache is recommended only for an item with p95 of at least 1 ms that is at least a quarter of the total, and none when the total is below 5 ms.")
}

# ---- files -----------------------------------------------------------------------------------------------------
$csv = New-Object System.Collections.Generic.List[string]
$csv.Add('id,script,clients,user,transactions,tps,p50_ms,p95_ms,p99_ms,max_ms,failed')
foreach ($r in $script:Results) {
    $csv.Add(('{0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10}' -f $r.Id, $r.Script, $r.Clients, $r.User, $r.Count,
        $r.Tps.ToString('0.00', $inv), $r.P50.ToString('0.000', $inv), $r.P95.ToString('0.000', $inv),
        $r.P99.ToString('0.000', $inv), $r.Max.ToString('0.000', $inv), $r.Failed))
}
Set-Content -Path (Join-Path $ResultDir 'raw.csv') -Value $csv -Encoding UTF8
Set-Content -Path (Join-Path $ResultDir 'environment.txt') -Value $env -Encoding UTF8

$md = New-Object System.Collections.Generic.List[string]
$md.Add("# Gate G1 benchmark result ($Size run, $stamp)")
$md.Add('')
$md.Add('Judged against ADR-0082 exactly as written. A PASS is a PASS only for what was measured, on the machine and data size below.')
$md.Add('')
if ($Size -eq 'smoke') { $md.Add('**This is a smoke run on tiny data. It proves the harness works. It is NOT a measurement and says nothing about the gate.**'); $md.Add('') }
$md.Add('## Verdict')
$md.Add('')
$md.Add('| Id | What | Measured | Limit | Result |')
$md.Add('|----|------|----------|-------|--------|')
foreach ($v in $script:Verdicts) {
    $measured = 'not measured'
    if ($null -ne $v.Measured) { $measured = "$($v.Measured) $($v.Unit)" }
    $limit = "$($v.Limit) $($v.Unit)"
    switch ($v.Rule) { 'ge' { $limit = "at least $limit" } 'le' { $limit = "at most $limit" } 'zero' { $limit = 'none' } }
    $md.Add("| $($v.Id) | $($v.What) | $measured | $limit | **$($v.Result)** |")
}
$failed = @($script:Verdicts | Where-Object { $_.Result -eq 'FAIL' })
$unmeasured = @($script:Verdicts | Where-Object { $_.Result -eq 'NOT MEASURED' })
$md.Add('')
if ($failed.Count -eq 0 -and $unmeasured.Count -eq 0 -and $script:Notes.Count -eq 0) {
    $md.Add('**All thresholds met in this result file.** (Subject to the limits stated in ADR-0082: data size, machine, what was not measured.)')
} else {
    $md.Add("**The gate does not pass on this result file:** $($failed.Count) threshold(s) failed, $($unmeasured.Count) not measured, $($script:Notes.Count) phase(s) failed.")
}
$md.Add('')
if ($script:Notes.Count -gt 0) { $md.Add('## Problems while running'); $md.Add(''); foreach ($n in $script:Notes) { $md.Add("- $n") }; $md.Add('') }
if ($script:Info.Count -gt 0) { $md.Add('## Facts reported (not pass or fail)'); $md.Add(''); foreach ($n in $script:Info) { $md.Add("- $n") }; $md.Add('') }
if ($carried.Count -gt 0) { $md.Add('## Carried notes (Sprints 2, 3 and 5)'); $md.Add(''); foreach ($n in $carried) { $md.Add($n) }; $md.Add('') }
$md.Add('## Every scenario')
$md.Add('')
$md.Add('Latency of one whole transaction in milliseconds (including the two setting statements, begin and commit).')
$md.Add('')
$md.Add('| Scenario | Clients | Role | Transactions | Per second | p50 | p95 | p99 | Max | Failed |')
$md.Add('|----------|---------|------|--------------|-----------|-----|-----|-----|-----|--------|')
foreach ($r in $script:Results) {
    $md.Add(('| {0} | {1} | {2} | {3} | {4} | {5} | {6} | {7} | {8} | {9} |' -f $r.Id, $r.Clients, $r.User, $r.Count,
        $r.Tps.ToString('0.0', $inv), $r.P50.ToString('0.00', $inv), $r.P95.ToString('0.00', $inv),
        $r.P99.ToString('0.00', $inv), $r.Max.ToString('0.0', $inv), $r.Failed))
}
$md.Add('')
$md.Add('## Sizes after the load')
$md.Add('')
$md.Add("Database: $dbSize")
$md.Add('')
$md.Add('| Table | Total | Table | Indexes | Estimated rows |')
$md.Add('|-------|-------|-------|---------|----------------|')
foreach ($line in $sizes) { $f = $line.Split('|'); $md.Add("| $($f -join ' | ') |") }
$md.Add('')
$md.Add('Indexes of the record table:')
$md.Add('')
foreach ($line in $indexSizes) { $f = $line.Split('|'); $md.Add("- $($f[0]): $($f[1])") }
$md.Add('')
$md.Add('Largest objects by record count: ' + (($rowsByObject | ForEach-Object { $_.Replace('|', ' = ') }) -join '; '))
$md.Add('')
$md.Add('## Environment')
$md.Add('')
foreach ($e in $env) { $md.Add("- $e") }
$md.Add('')
$md.Add('## Not measured (see ADR-0082)')
$md.Add('')
$md.Add('Ten million records in one object (unless the run was made with -Size large); a production-grade server; authorization predicates of record-level security; field security; formula and validation work on write; the application, its pool and the network; full text search; reports; backup and failover.')
Set-Content -Path (Join-Path $ResultDir 'summary.md') -Value $md -Encoding UTF8

if (-not $KeepContainer) { Remove-BenchContainer; Say 'Benchmark container and data removed.' } else { Say "Container $Container kept (remove with -CleanupOnly)." }
Say "Done. Read: $(Join-Path $ResultDir 'summary.md')"
if ($failed.Count -gt 0 -or $unmeasured.Count -gt 0) { exit 2 }
