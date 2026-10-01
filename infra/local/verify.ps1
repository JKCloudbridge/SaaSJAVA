<#
 Functional smoke test of the local environment (more than "the port is open"):
   PostgreSQL  - runs a query, checks row level security is available
   Redis       - authenticates and round-trips a key
   Object store- creates a bucket, uploads and downloads an object through the S3 API
   Mail catcher- sends a message over SMTP and reads it back through the API
   Trace viewer- the collector accepts a trace payload and the UI answers
 Run after `docker compose up -d`. Exit code is non-zero if anything fails.
#>
$ErrorActionPreference = 'Continue'  # native tools write progress to stderr; failures are thrown explicitly
Set-Location $PSScriptRoot

$envMap = @{}
Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $envMap[$k] = $v
}
$failures = @()

function Step([string]$name, [scriptblock]$body) {
    try {
        & $body
        Write-Host "[ OK ] $name"
    } catch {
        Write-Host "[FAIL] $name : $($_.Exception.Message)"
        $script:failures += $name
    }
}

Step 'PostgreSQL query and row level security' {
    $sql = "create temp table probe(id int); alter table probe enable row level security; " +
           "select current_setting('server_version') as v, (select relrowsecurity from pg_class where relname='probe') as rls;"
    $out = docker compose exec -T postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB -tA -F '|' -c $sql 2>&1 | Out-String
    if ($out -notmatch '18\.\d+.*\|t') { throw "unexpected output: $out" }
}

Step 'Application database role is unprivileged and the tenant policy works' {
    $role = docker compose exec -T postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB -tA -F '|' -c "select rolsuper, rolbypassrls, rolcreatedb, rolcreaterole, rolcanlogin from pg_roles where rolname = '$($envMap.APP_DB_USER)'" 2>&1 | Out-String
    # Superuser, bypass-RLS, create-database and create-role all false; login true.
    if ($role.Trim() -ne 'f|f|f|f|t') { throw "the application role is missing or too powerful (run ./init-app-role.ps1): $role" }
    # The functional check needs the schema, which exists once the application has started and migrated once.
    $has = docker compose exec -T postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB -tA -c "select to_regprocedure('platform_current_tenant()') is not null" 2>&1 | Out-String
    if ($has -match 't') {
        $sql = "begin; create temp table verify_probe (tenant_id uuid not null); alter table verify_probe enable row level security; " +
               "alter table verify_probe force row level security; create policy p on verify_probe using (tenant_id = (select platform_current_tenant())) " +
               "with check (tenant_id = (select platform_current_tenant())); select set_config('app.current_tenant', '00000000-0000-0000-0000-00000000000a', true); " +
               "insert into verify_probe values ('00000000-0000-0000-0000-00000000000a'); select count(*) from verify_probe; " +
               "select set_config('app.current_tenant', '', true); select count(*) from verify_probe; rollback;"
        $out = $sql | docker compose exec -T -e "PGPASSWORD=$($envMap.APP_DB_PASSWORD)" postgres psql -h 127.0.0.1 -U $envMap.APP_DB_USER -d $envMap.POSTGRES_DB -tA 2>&1 | Out-String
        # One row visible while the tenant is set, none once it is cleared: the two lines that are plain numbers.
        $counts = @($out -split "`r?`n" | Where-Object { $_ -match '^\d+$' })
        if (($counts -join ',') -ne '1,0') { throw "the tenant policy did not isolate as the application role: $out" }
    }
}

Step 'Redis authentication and round trip' {
    $out = docker compose exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning set probe ok EX 30 && redis-cli -a "$REDIS_PASSWORD" --no-auth-warning get probe' 2>&1 | Out-String
    if ($out -notmatch 'OK' -or $out -notmatch 'ok') { throw "unexpected output: $out" }
    $noAuth = docker compose exec -T redis redis-cli get probe 2>&1 | Out-String
    if ($noAuth -notmatch 'NOAUTH') { throw "Redis accepted an unauthenticated command" }
}

Step 'Object storage bucket, upload, download (S3 API)' {
    $net = 'platform-local_default'
    $script = 'set -e; echo hello-local > /tmp/probe.txt; ' +
              'aws --endpoint-url http://object-storage:8333 s3 mb s3://probe-bucket 2>/dev/null || true; ' +
              'aws --endpoint-url http://object-storage:8333 s3 cp /tmp/probe.txt s3://probe-bucket/probe.txt; ' +
              'aws --endpoint-url http://object-storage:8333 s3 cp s3://probe-bucket/probe.txt - '
    $out = docker run --rm --network $net -e AWS_ACCESS_KEY_ID=$($envMap.S3_ACCESS_KEY) -e AWS_SECRET_ACCESS_KEY=$($envMap.S3_SECRET_KEY) `
        -e AWS_DEFAULT_REGION=us-east-1 --entrypoint sh amazon/aws-cli:latest -c $script 2>&1 | Out-String
    if ($out -notmatch 'hello-local') { throw "unexpected output: $out" }
}

Step 'Mail catcher receives and exposes a message' {
    $smtp = New-Object System.Net.Mail.SmtpClient('127.0.0.1', [int]$envMap.MAIL_SMTP_PORT)
    $subject = "verify-$([guid]::NewGuid())"
    $smtp.Send('sender@example.test', 'user-a@example.test', $subject, 'local environment check')
    $smtp.Dispose()
    Start-Sleep -Seconds 1
    $messages = Invoke-RestMethod "http://127.0.0.1:$($envMap.MAIL_UI_PORT)/api/v1/messages"
    if (-not ($messages.messages | Where-Object { $_.Subject -eq $subject })) { throw 'message not found in catcher' }
}

Step 'Trace viewer accepts traces and answers queries' {
    $payload = '{"resourceSpans":[]}'
    $ingest = Invoke-WebRequest -Uri "http://127.0.0.1:$($envMap.TRACING_COLLECTOR_PORT)/v1/traces" -Method Post -ContentType 'application/json' -Body $payload -UseBasicParsing
    if ($ingest.StatusCode -ne 200) { throw "collector answered $($ingest.StatusCode)" }
    $ui = Invoke-WebRequest -Uri "http://127.0.0.1:$($envMap.TRACING_UI_PORT)/" -UseBasicParsing
    if ($ui.StatusCode -ne 200) { throw "UI answered $($ui.StatusCode)" }
}

if ($failures.Count -gt 0) {
    Write-Host "`n$($failures.Count) check(s) failed."
    exit 1
}
Write-Host "`nAll local environment checks passed."
