<#
 The measurement of decision D3 (ADR-0133): does the outbox and its polling relay carry the events of record changes
 through the workflow handler? The thresholds T1 to T6 are written in ADR-0133 and were fixed before any number
 existed; this script only measures and judges. A threshold is never changed after a number is seen.

 What it needs: the local services running (infra/local: docker compose up -d, init-app-role.ps1) and the application
 running with the `local` profile (the real relay and the real handler are what is measured). For a clean
 measurement start the application with PLATFORM_WORKFLOW_ENABLED=false, so that the worker does not run the
 executions the handler makes while the relay drains the events. For T3 start three instances against the same
 database (different PLATFORM_HTTP_PORT and PLATFORM_MANAGEMENT_PORT) and pass -BaselineRate, the T1 rate of the
 one-instance run.

 It writes the data it needs with SQL as the owner role (organizations, one object, workflows, records, events),
 never touches an organization it did not make (their slugs start with `bench-`), and leaves a summary in
 benchmark/results/<time>-workflow-<size>/. -Size smoke proves the harness runs; it is NOT a measurement.

   .\benchmark\workflow\run-event-benchmark.ps1 -Size smoke
   .\benchmark\workflow\run-event-benchmark.ps1                      # one instance: T1, T2, T4, T5, T6
   .\benchmark\workflow\run-event-benchmark.ps1 -BaselineRate 140    # three instances: also T3
   .\benchmark\workflow\run-event-benchmark.ps1 -CleanupOnly         # remove what earlier runs left behind

 The exit code is 0 when every judged threshold was met, 2 otherwise.

 -Actions update (Sprint 23, ADR-0138 to ADR-0140): the same question for workflows that WRITE. The thresholds A1 to A6 are
 written below and in ADR-0133 ("Sprint 23: write actions") before the script was ever run and are never changed after a
 number is seen. This mode needs the application running WITH the dispatcher (PLATFORM_WORKFLOW_ENABLED=true, the
 default) because the actions are what is measured, and it measures A1 to A6 only (the T1 to T6 run is the one above):

   .\benchmark\workflow\run-event-benchmark.ps1 -Actions update -Size smoke
   .\benchmark\workflow\run-event-benchmark.ps1 -Actions update                          # one instance: A1, A2, A3, A5, A6
   .\benchmark\workflow\run-event-benchmark.ps1 -Actions update -BaselineRate 60         # three instances: also A4 (the A1 of one)

 It is the product owner's to run, on the intended hardware.
#>
param(
    [ValidateSet('smoke', 'default')] [string]$Size = 'default',
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\..\infra\local\.env'),
    [string]$ComposeFile = (Join-Path $PSScriptRoot '..\..\infra\local\docker-compose.yml'),
    [string]$ManagementUrl = 'http://localhost:8081',
    [double]$BaselineRate = 0,
    [ValidateSet('none', 'update')] [string]$Actions = 'none',
    [switch]$CleanupOnly
)

$ErrorActionPreference = 'Stop'
$SystemActor = '00000000-0000-0000-0000-000000000000'

# ---- the thresholds of ADR-0133, as written there ----
$T1 = 100      # events per second, one instance, events that match workflows
$T2 = 200      # events per second, events of an organization with no workflow
$T3 = 1.8      # three instances, times T1
$T4P95 = 3.0   # seconds from commit to execution row at half of T1's rate
$T4P99 = 10.0
$T5 = 1.05     # p95 of a record write while the handler works, against the same while it has nothing to do
$T6 = 0.001    # seconds, p95 of the handler for an organization with no workflow

# ---- the thresholds of ADR-0133 for workflows that write (Sprint 23), fixed before any number existed ----
$A1 = 50       # completed workflow updates per second, one instance; the backlog must not grow
$A2P95 = 5.0   # seconds from the commit of the event to the commit of the action's write, at half of A1's rate
$A2P99 = 15.0
$A3 = 1.0      # record.updated events made per action: exactly one, no runaway
$A4 = 1.8      # three instances, times A1
$A5 = 200      # events per second for an organization with no workflow, re-measured while actions run (T6 unchanged)
$A6 = 1.10     # p95 of a person's record write while actions run, against the same while they do not

$sizes = @{
    smoke   = @{ Backlog1 = 1000;  Backlog2 = 2000;   PacedSeconds = 15;  WriterSeconds = 15; Judged = $false; Records = 300;  Paced = 20 }
    default = @{ Backlog1 = 60000; Backlog2 = 120000;  PacedSeconds = 120; WriterSeconds = 60; Judged = $true;  Records = 20000; Paced = 120 }
}
$plan = $sizes[$Size]

function Read-Env($path) {
    if (-not (Test-Path $path)) { throw "Missing $path. Run infra/local/init-env.ps1 first." }
    $values = @{}
    foreach ($line in Get-Content $path) {
        if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}

$settings = Read-Env $EnvFile
$container = (docker compose -f $ComposeFile ps -q postgres).Trim()
if (-not $container) { throw 'The postgres service of infra/local is not running.' }

function Psql([string]$sql) {
    $out = docker exec -i -e PGPASSWORD=$($settings['POSTGRES_PASSWORD']) $container psql -U $settings['POSTGRES_USER'] `
        -d $settings['POSTGRES_DB'] -v ON_ERROR_STOP=1 -At -c $sql
    if ($LASTEXITCODE -ne 0) { throw "psql failed for: $sql" }
    return ($out -join "`n").Trim()
}

function Remove-Benchmark {
    Write-Host 'Removing the benchmark organizations and what they own...'
    $ids = Psql "select id from tenant where slug like 'bench-%'"
    foreach ($id in ($ids -split "`n" | Where-Object { $_ })) {
        $tables = 'workflow_step', 'workflow_execution', 'workflow_schedule', 'workflow_version', 'workflow',
                  'field_definition', 'object_definition', 'metadata_version', 'outbox_event', 'processed_event',
                  'object_record'
        foreach ($table in $tables) {
            # workflow_step goes with its execution; the others are deleted by organization.
            if ($table -ne 'workflow_step') { Psql "delete from $table where tenant_id = '$id'" | Out-Null }
        }
        Psql "delete from tenant where id = '$id'" | Out-Null
    }
}

if ($CleanupOnly) { Remove-Benchmark; return }

# ---------------------------------------------------------------- the data
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$resultDir = Join-Path $PSScriptRoot "..\results\$stamp-workflow-$Size"
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 8)

function New-Organization([string]$label) {
    $id = [guid]::NewGuid().ToString()
    Psql "insert into tenant (id, slug, display_name, created_by, updated_by) values ('$id', 'bench-$label-$suffix', 'Benchmark $label', '$SystemActor', '$SystemActor')" | Out-Null
    Psql "update tenant set status = 'ACTIVE', updated_by = '$SystemActor', version = version + 1 where id = '$id'" | Out-Null
    Psql "insert into object_definition (tenant_id, api_name, label, plural_label, created_by, updated_by) values ('$id', 'Bench__c', 'Bench', 'Benches', '$SystemActor', '$SystemActor')" | Out-Null
    foreach ($field in 'status__c', 'other__c') {
        Psql "insert into field_definition (tenant_id, object_api_name, api_name, label, data_type, config, sort_order, created_by, updated_by) values ('$id', 'Bench__c', '$field', '$field', 'TEXT', cast('{""maxLength"":255}' as jsonb), 10, '$SystemActor', '$SystemActor')" | Out-Null
    }
    return $id
}

function Add-Workflows([string]$tenant) {
    # 10 on RECORD_UPDATED, 5 on FIELD_CHANGED(status__c) that match the benchmark events, 5 on a field that never changes.
    $kinds = @()
    1..10 | ForEach-Object { $kinds += @{ n = "Updated$_"; kind = 'RECORD_UPDATED'; fields = '' } }
    1..5 | ForEach-Object { $kinds += @{ n = "Status$_"; kind = 'FIELD_CHANGED'; fields = '"status__c"' } }
    1..5 | ForEach-Object { $kinds += @{ n = "Other$_"; kind = 'FIELD_CHANGED'; fields = '"other__c"' } }
    foreach ($k in $kinds) {
        $trigger = if ($k.fields) { "{""kind"":""$($k.kind)"",""fields"":[$($k.fields)]}" } else { "{""kind"":""$($k.kind)""}" }
        $workflow = [guid]::NewGuid().ToString()
        Psql "insert into workflow (id, tenant_id, object_api_name, api_name, label, created_by, updated_by) values ('$workflow', '$tenant', 'Bench__c', '$($k.n)__c', '$($k.n)', '$SystemActor', '$SystemActor')" | Out-Null
        Psql "insert into workflow_version (tenant_id, workflow_id, version_number, trigger_kind, trigger, actions, created_by, updated_by) values ('$tenant', '$workflow', 1, '$($k.kind)', cast('$trigger' as jsonb), cast('[{""type"":""PUBLISH_EVENT"",""name"":""Tell"",""settings"":{""event"":""bench.event""}}]' as jsonb), '$SystemActor', '$SystemActor')" | Out-Null
    }
}

function Add-Events([string]$tenant, [int]$count) {
    Psql "insert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) select '$tenant', 'record.updated', jsonb_build_object('object', 'Bench__c', 'recordId', gen_random_uuid(), 'version', 1, 'fields', jsonb_build_array('status__c')), '$SystemActor', '$SystemActor' from generate_series(1, $count)" | Out-Null
}

function Pending([string]$tenant) {
    return [int](Psql "select count(*) from outbox_event where tenant_id = '$tenant' and status = 'PENDING'")
}

function Measure-Drain([string]$tenant, [int]$count) {
    Add-Events $tenant $count
    $started = Get-Date
    $deadline = $started.AddMinutes(25)
    while ((Pending $tenant) -gt 0) {
        if ((Get-Date) -gt $deadline) { throw "The backlog of $count events did not drain in 25 minutes." }
        Start-Sleep -Milliseconds 500
    }
    $seconds = ((Get-Date) - $started).TotalSeconds
    return [math]::Round($count / [math]::Max($seconds, 0.001), 1)
}

# A percentile from the cumulative buckets of a Prometheus histogram.
function Get-HandlingP95([string]$result) {
    $text = (Invoke-WebRequest -UseBasicParsing "$ManagementUrl/actuator/prometheus").Content
    $buckets = @()
    foreach ($line in ($text -split "`n")) {
        if ($line -match "^platform_workflow_event_handling_seconds_bucket\{.*le=""([^""]+)"".*result=""$result""|^platform_workflow_event_handling_seconds_bucket\{.*result=""$result"".*le=""([^""]+)""") {
            $le = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
            $count = [double](($line -split ' ')[-1])
            $buckets += [pscustomobject]@{ Le = $(if ($le -eq '+Inf') { [double]::PositiveInfinity } else { [double]$le }); Count = $count }
        }
    }
    if ($buckets.Count -eq 0) { return $null }
    $buckets = $buckets | Sort-Object Le
    $total = $buckets[-1].Count
    foreach ($bucket in $buckets) { if ($bucket.Count -ge 0.95 * $total) { return $bucket.Le } }
    return $null
}

# ---------------------------------------------------------------- the run
$results = [ordered]@{}
$lines = New-Object System.Collections.Generic.List[string]
function Note([string]$text) { Write-Host $text; $lines.Add($text) }

# ---------------------------------------------------------------- workflows that write (Sprint 23)
if ($Actions -eq 'update') {
    Note "# Workflow write-action benchmark ($Size) $stamp"
    Note ''
    if (-not $plan.Judged) { Note '**Smoke run: it proves the harness works. Nothing here is a measurement and nothing is judged.**' }
    Note 'The application must run WITH the dispatcher (PLATFORM_WORKFLOW_ENABLED=true): the actions are what is measured.'

    $acts = New-Organization 'acts'
    $idle = New-Organization 'idle'
    # The trial plan has the feature `workflows`; a subscription is what makes the organization entitled to run them.
    Psql "insert into subscription (bound_tenant_id, plan_id, status, trial_ends_at, created_by, updated_by) select '$acts', id, 'TRIAL', now() + interval '30 days', '$SystemActor', '$SystemActor' from plan where key = 'trial'" | Out-Null
    $workflow = [guid]::NewGuid().ToString()
    $trigger = '{""kind"":""FIELD_CHANGED"",""fields"":[""status__c""]}'
    $step = '[{""type"":""UPDATE_RECORD"",""name"":""Mark"",""settings"":{},""assignments"":[{""field"":""other__c"",""value"":""done""}]}]'
    Psql "insert into workflow (id, tenant_id, object_api_name, api_name, label, created_by, updated_by) values ('$workflow', '$acts', 'Bench__c', 'Writer__c', 'Writer', '$SystemActor', '$SystemActor')" | Out-Null
    Psql "insert into workflow_version (tenant_id, workflow_id, version_number, trigger_kind, trigger, actions, created_by, updated_by) values ('$acts', '$workflow', 1, 'FIELD_CHANGED', cast('$trigger' as jsonb), cast('$step' as jsonb), '$SystemActor', '$SystemActor')" | Out-Null
    Note "Organizations made: with one writing workflow ($acts), with none ($idle)."
    Start-Sleep -Seconds 3

    # Real records for the events to name: each is changed once, so every execution makes a write.
    function Add-Records([int]$count) {
        Psql "insert into object_record (tenant_id, object_api_name, owner_id, payload, created_by, updated_by) select '$acts', 'Bench__c', '$SystemActor', jsonb_build_object('status__c', 'new'), '$SystemActor', '$SystemActor' from generate_series(1, $count)" | Out-Null
    }
    function Add-Triggers([int]$count) {
        Psql "insert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) select '$acts', 'record.updated', jsonb_build_object('object', 'Bench__c', 'recordId', id, 'version', 1, 'fields', jsonb_build_array('status__c')), '$SystemActor', '$SystemActor' from (select id from object_record where tenant_id = '$acts' and object_api_name = 'Bench__c' and payload ->> 'other__c' is null order by id limit $count) as chosen" | Out-Null
    }
    function Unfinished { return [int](Psql "select count(*) from workflow_execution where tenant_id = '$acts' and status in ('QUEUED', 'RUNNING')") }

    # A1 and A3: a backlog of triggers drained by the relay, the handler and the dispatcher.
    Add-Records $plan.Records
    $started = Get-Date
    Add-Triggers $plan.Records
    $deadline = $started.AddMinutes(30)
    while ((Pending $acts) -gt 0 -or (Unfinished) -gt 0) {
        if ((Get-Date) -gt $deadline) { throw 'The backlog of triggers did not drain in 30 minutes.' }
        Start-Sleep -Milliseconds 500
    }
    $seconds = ((Get-Date) - $started).TotalSeconds
    $results.A1 = [math]::Round($plan.Records / [math]::Max($seconds, 0.001), 1)
    $completed = [int](Psql "select count(*) from workflow_execution where tenant_id = '$acts' and status = 'COMPLETED'")
    $written = [int](Psql "select count(*) from outbox_event where tenant_id = '$acts' and event_type = 'record.updated' and payload ? 'chain'")
    $results.A3 = if ($completed -gt 0) { [math]::Round($written / $completed, 3) } else { $null }
    Note "A1: $($results.A1) workflow updates/s ($completed of $($plan.Records) completed)."
    Note "A3: $written record.updated events with a chain for $completed completed actions: $($results.A3) per action."

    # A2: arrival at half of A1; the delay from the trigger's commit to the commit of the action's write.
    $arrival = [int]([math]::Max(1, $A1 / 2))
    Add-Records ($arrival * $plan.Paced)
    $paceStart = (Psql 'select clock_timestamp()')
    for ($second = 0; $second -lt $plan.Paced; $second++) {
        $tick = Get-Date
        Add-Triggers $arrival
        $left = 1000 - ((Get-Date) - $tick).TotalMilliseconds
        if ($left -gt 0) { Start-Sleep -Milliseconds $left }
    }
    while ((Pending $acts) -gt 0 -or (Unfinished) -gt 0) { Start-Sleep -Milliseconds 500 }
    $delays = Psql "select round(percentile_cont(0.95) within group (order by extract(epoch from (e.finished_at - o.created_at)))::numeric, 3) || ' ' || round(percentile_cont(0.99) within group (order by extract(epoch from (e.finished_at - o.created_at)))::numeric, 3) from workflow_execution e join outbox_event o on o.id = e.event_id where o.tenant_id = '$acts' and o.created_at >= '$paceStart' and e.status = 'COMPLETED'"
    $p95, $p99 = $delays -split ' '
    $results.A2p95 = [double]$p95
    $results.A2p99 = [double]$p99
    Note "A2: at $arrival triggers/s the delay from commit to the committed write is p95 $p95 s, p99 $p99 s."

    # A4: three instances, as A1, against the baseline of the one-instance run.
    if ($BaselineRate -gt 0) {
        $results.A4 = [math]::Round($results.A1 / $BaselineRate, 2)
        Note "A4: $($results.A4) x the one-instance rate of $BaselineRate."
    }

    # A5: an organization with no workflow, drained while the actions of the other run.
    Add-Records $plan.Records
    Add-Triggers $plan.Records
    $results.A5 = Measure-Drain $idle $plan.Backlog2
    Note "A5: $($results.A5) events/s for an organization with no workflow while actions run."

    # A6: person's writes (8 pgbench clients) while actions run, then while they do not.
    function Measure-PersonWrites([switch]$WithActions) {
        if ($WithActions) { Add-Records $plan.Records; Add-Triggers $plan.Records }
        $script = "\set off random(0, 99)`nBEGIN;`nupdate object_record set version = version + 1, updated_by = '$SystemActor' where id = (select id from object_record where tenant_id = '$idle' and object_api_name = 'Bench__c' order by id offset :off limit 1);`ninsert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) values ('$idle', 'bench.write', '{}'::jsonb, '$SystemActor', '$SystemActor');`nEND;`n"
        $local = Join-Path $resultDir 'person-writes.sql'
        Set-Content -Path $local -Value $script -Encoding ascii
        docker cp $local "${container}:/tmp/person-writes.sql" | Out-Null
        docker exec -e PGPASSWORD=$($settings['POSTGRES_PASSWORD']) $container sh -c "rm -f /tmp/pgp.*; pgbench -n -c 8 -j 4 -T $($plan.WriterSeconds) -l --log-prefix=/tmp/pgp -f /tmp/person-writes.sql -U $($settings['POSTGRES_USER']) $($settings['POSTGRES_DB']) > /dev/null"
        $raw = docker exec $container sh -c 'cat /tmp/pgp.*'
        $micros = $raw | ForEach-Object { [double](($_ -split ' ')[2]) } | Sort-Object
        if ($micros.Count -eq 0) { throw 'pgbench wrote no log.' }
        return $micros[[int][math]::Floor(0.95 * ($micros.Count - 1))] / 1000.0
    }
    Psql "insert into object_record (tenant_id, object_api_name, owner_id, payload, created_by, updated_by) select '$idle', 'Bench__c', '$SystemActor', '{}'::jsonb, '$SystemActor', '$SystemActor' from generate_series(1, 100)" | Out-Null
    $quiet = Measure-PersonWrites
    $busy = Measure-PersonWrites -WithActions
    while ((Pending $acts) -gt 0 -or (Unfinished) -gt 0) { Start-Sleep -Milliseconds 500 }
    $results.A6 = [math]::Round($busy / [math]::Max($quiet, 0.001), 3)
    Note "A6: a person's write p95 $busy ms while actions run, $quiet ms while they do not: ratio $($results.A6)."

    Note ''
    Note '| Threshold | Measured | Pass |'
    Note '|-----------|----------|------|'
    $failed = $false
    function JudgeA([string]$name, $measured, [scriptblock]$passes) {
        $pass = if ($measured -eq $null) { $null } else { & $passes $measured }
        $text = if ($pass -eq $null) { 'not measured' } elseif ($pass) { 'PASS' } else { 'FAIL' }
        if ($plan.Judged -and $pass -eq $false) { $script:failed = $true }
        Note "| $name | $measured | $(if ($plan.Judged) { $text } else { 'not judged (smoke)' }) |"
    }
    JudgeA "A1 >= $A1 updates/s" $results.A1 { param($m) $m -ge $A1 }
    JudgeA "A2 p95 <= $A2P95 s" $results.A2p95 { param($m) $m -le $A2P95 }
    JudgeA "A2 p99 <= $A2P99 s" $results.A2p99 { param($m) $m -le $A2P99 }
    JudgeA "A3 = $A3 event per action" $results.A3 { param($m) $m -eq $A3 }
    JudgeA "A4 >= $A4 x A1" $results.A4 { param($m) $m -ge $A4 }
    JudgeA "A5 >= $A5 events/s" $results.A5 { param($m) $m -ge $A5 }
    JudgeA "A6 <= $A6 x" $results.A6 { param($m) $m -le $A6 }
    Set-Content -Path (Join-Path $resultDir 'summary.md') -Value $lines -Encoding utf8
    Note ''
    Note "Written to $resultDir. The benchmark organizations remain; remove them with -CleanupOnly."
    if ($failed) { exit 2 }
    exit 0
}

Note "# Workflow event benchmark ($Size) $stamp"
Note ''
if (-not $plan.Judged) { Note '**Smoke run: it proves the harness works. Nothing here is a measurement and nothing is judged.**' }

$with = New-Organization 'with'
$without = New-Organization 'without'
Add-Workflows $with
Note "Organizations made: with 20 workflows ($with), with none ($without)."
Start-Sleep -Seconds 3   # let the metadata version settle for the first snapshot

# T1: events that match workflows, drained by the relay and the handler.
$results.T1 = Measure-Drain $with $plan.Backlog1
$made = [int](Psql "select count(*) from workflow_execution where tenant_id = '$with'")
Note "T1: $($results.T1) events/s ($($plan.Backlog1) events, $made executions made; 15 per event are expected: $($plan.Backlog1 * 15))."
if ($made -ne $plan.Backlog1 * 15) { Note '**T1 correctness: the number of executions is not 15 per event.**'; $results.Correct = $false }

# T2: events of an organization with no workflow.
$results.T2 = Measure-Drain $without $plan.Backlog2
Note "T2: $($results.T2) events/s ($($plan.Backlog2) events)."

# T6: the handler for an organization with no workflow, from the histogram of the application.
$results.T6 = Get-HandlingP95 'no_workflow'
Note "T6: p95 of the handler for an organization with no workflow: $($results.T6) s (upper bound of the bucket; the application serves $ManagementUrl)."

# T3: three instances, as T1, against the baseline of the one-instance run.
if ($BaselineRate -gt 0) {
    $results.T3 = [math]::Round($results.T1 / $BaselineRate, 2)
    Note "T3: $($results.T3) x the one-instance rate of $BaselineRate."
}

# T4: arrival at half of T1, delay from the commit of the event to the execution row.
$arrival = [int]($T1 / 2)
$paceStart = (Psql 'select clock_timestamp()')
for ($second = 0; $second -lt $plan.PacedSeconds; $second++) {
    $tick = Get-Date
    Add-Events $with $arrival
    $left = 1000 - ((Get-Date) - $tick).TotalMilliseconds
    if ($left -gt 0) { Start-Sleep -Milliseconds $left }
}
while ((Pending $with) -gt 0) { Start-Sleep -Milliseconds 500 }
$delays = Psql "select round(percentile_cont(0.95) within group (order by extract(epoch from (e.created_at - o.created_at)))::numeric, 3) || ' ' || round(percentile_cont(0.99) within group (order by extract(epoch from (e.created_at - o.created_at)))::numeric, 3) from workflow_execution e join outbox_event o on o.id = e.event_id where o.tenant_id = '$with' and o.created_at >= '$paceStart'"
$p95, $p99 = $delays -split ' '
$results.T4p95 = [double]$p95
$results.T4p99 = [double]$p99
Note "T4: at $arrival events/s the delay from commit to execution row is p95 $p95 s, p99 $p99 s."

# T5: writers (8 pgbench clients) while the relay drains events with work, then while it drains events with none.
function Measure-Writers([string]$drained, [int]$backlog) {
    Add-Events $drained $backlog
    $script = "\set off random(0, 99)`nBEGIN;`nupdate object_record set version = version + 1, updated_by = '$SystemActor' where id = (select id from object_record where tenant_id = '$with' and object_api_name = 'Bench__c' order by id offset :off limit 1);`ninsert into outbox_event (tenant_id, event_type, payload, created_by, updated_by) values ('$with', 'bench.write', '{}'::jsonb, '$SystemActor', '$SystemActor');`nEND;`n"
    $local = Join-Path $resultDir 'writers.sql'
    Set-Content -Path $local -Value $script -Encoding ascii
    docker cp $local "${container}:/tmp/writers.sql" | Out-Null
    docker exec -e PGPASSWORD=$($settings['POSTGRES_PASSWORD']) $container sh -c "rm -f /tmp/pgb.*; pgbench -n -c 8 -j 4 -T $($plan.WriterSeconds) -l --log-prefix=/tmp/pgb -f /tmp/writers.sql -U $($settings['POSTGRES_USER']) $($settings['POSTGRES_DB']) > /dev/null"
    $raw = docker exec $container sh -c 'cat /tmp/pgb.*'
    $micros = $raw | ForEach-Object { [double](($_ -split ' ')[2]) } | Sort-Object
    if ($micros.Count -eq 0) { throw 'pgbench wrote no log.' }
    return $micros[[int][math]::Floor(0.95 * ($micros.Count - 1))] / 1000.0   # milliseconds
}
Psql "insert into object_record (tenant_id, object_api_name, owner_id, payload, created_by, updated_by) select '$with', 'Bench__c', '$SystemActor', '{}'::jsonb, '$SystemActor', '$SystemActor' from generate_series(1, 100)" | Out-Null
$busy = Measure-Writers $with ([int]($plan.Backlog1 / 2))
while ((Pending $with) -gt 0) { Start-Sleep -Milliseconds 500 }
$idle = Measure-Writers $without ([int]($plan.Backlog2 / 2))
while ((Pending $without) -gt 0) { Start-Sleep -Milliseconds 500 }
$results.T5 = [math]::Round($busy / [math]::Max($idle, 0.001), 3)
Note "T5: writer p95 $busy ms while the handler works, $idle ms while it has nothing to do: ratio $($results.T5)."

# ---------------------------------------------------------------- the verdict
Note ''
Note '| Threshold | Measured | Pass |'
Note '|-----------|----------|------|'
$failed = $false
function Judge([string]$name, $measured, [scriptblock]$passes) {
    $pass = if ($measured -eq $null) { $null } else { & $passes $measured }
    $text = if ($pass -eq $null) { 'not measured' } elseif ($pass) { 'PASS' } else { 'FAIL' }
    if ($plan.Judged -and $pass -eq $false) { $script:failed = $true }
    Note "| $name | $measured | $(if ($plan.Judged) { $text } else { 'not judged (smoke)' }) |"
}
Judge "T1 >= $T1 events/s" $results.T1 { param($m) $m -ge $T1 }
Judge "T2 >= $T2 events/s" $results.T2 { param($m) $m -ge $T2 }
Judge "T3 >= $T3 x T1" $results.T3 { param($m) $m -ge $T3 }
Judge "T4 p95 <= $T4P95 s" $results.T4p95 { param($m) $m -le $T4P95 }
Judge "T4 p99 <= $T4P99 s" $results.T4p99 { param($m) $m -le $T4P99 }
Judge "T5 <= $T5 x" $results.T5 { param($m) $m -le $T5 }
Judge "T6 p95 <= $T6 s" $results.T6 { param($m) $m -le $T6 }
if ($results.Correct -eq $false) { $failed = $true }

Set-Content -Path (Join-Path $resultDir 'summary.md') -Value $lines -Encoding utf8
Note ''
Note "Written to $resultDir. The benchmark organizations remain; remove them with -CleanupOnly."
if ($failed) { exit 2 }
exit 0
