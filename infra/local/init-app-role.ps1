<#
 Creates the application database role in the local PostgreSQL by running manual migration M001
 (db/manual/M001__create_application_role.sql) and sets its password from infra/local/.env (APP_DB_PASSWORD).

 Why a separate role: the application must connect as a role that owns nothing, cannot change the schema and cannot
 bypass row level security, or tenant isolation protects nothing (docs/adr/0015-row-level-security-implementation.md).
 The local database user (POSTGRES_USER) is the owner: it runs the migrations and runs M001.

 Run it once after `docker compose up -d` (and again whenever you like: it is repeatable). The password is sent to
 PostgreSQL through standard input, so it never appears on a command line or in a process list.
#>
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Test-Path .env)) { throw 'infra/local/.env is missing. Run ./init-env.ps1 first.' }
$envMap = @{}
Get-Content .env | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $envMap[$k] = $v
}
foreach ($key in 'POSTGRES_USER', 'POSTGRES_DB', 'APP_DB_USER', 'APP_DB_PASSWORD') {
    if (-not $envMap[$key]) { throw "$key is missing from .env. Run ./init-env.ps1 (it adds missing variables)." }
}
if ($envMap.APP_DB_USER -ne 'platform_app') {
    throw 'APP_DB_USER must be platform_app: that is the role manual migration M001 creates.'
}
if ($envMap.APP_DB_PASSWORD -notmatch '^[A-Za-z0-9]+$') {
    throw 'APP_DB_PASSWORD may contain only letters and digits (init-env.ps1 generates such a value).'
}

$script = Join-Path $PSScriptRoot '..\..\db\manual\M001__create_application_role.sql'
if (-not (Test-Path $script)) { throw "Manual migration not found: $script" }

Write-Host 'Running manual migration M001 as the owner role...'
Get-Content $script -Raw | docker compose exec -T postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "M001 failed (exit code $LASTEXITCODE)." }

Write-Host 'Setting the password of the application role...'
"alter role $($envMap.APP_DB_USER) password '$($envMap.APP_DB_PASSWORD)';" |
    docker compose exec -T postgres psql -U $envMap.POSTGRES_USER -d $envMap.POSTGRES_DB -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) { throw "Setting the password failed (exit code $LASTEXITCODE)." }

Write-Host "Done. The application role '$($envMap.APP_DB_USER)' exists and can log in. Check with ./verify.ps1."
