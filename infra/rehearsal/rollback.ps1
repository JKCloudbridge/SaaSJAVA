<#
 The rollback test (ADR-0288): the PREVIOUS release's image must run on the schema the NEW release migrated. That is the whole
 rollback rule: migrations only expand while a release can still be rolled back to the one before it, so the way back is the
 previous image and never an undo of the database. Rolling back is the same rolling release as going forward, with no migration
 job (there is nothing to undo), and it must also cause no failed request.

 Needs platform-app:previous (./build-previous-image.ps1) and a running rehearsal on the new release (./up.ps1, optionally
 ./release.ps1 first). The previous image is from before the migration job existed, so it still migrates on start with the owner's
 credentials, and its key files are mounted as files; the test gives it exactly that, as that release's own deployment would have.
#>
param([string]$PreviousImage = 'platform-app:previous', [int]$Baseline = 20, [int]$Settle = 20)
. "$PSScriptRoot/lib.ps1"
$null = Invoke-DockerQuiet image inspect $PreviousImage
if ($LASTEXITCODE -ne 0) { throw "The image $PreviousImage does not exist: run ./build-previous-image.ps1." }
$apis = Get-Replicas 'api'
if ($apis.Count -lt 2) { throw 'Start the rehearsal first (./up.ps1).' }
Invoke-Seed

$schema = Get-OwnerSql 'select platform_schema_version()'
Say "The schema is at version $schema (the new release's)."
$secrets = (Resolve-Path (Join-Path $PSScriptRoot 'secrets')).Path
$override = @{
    PLATFORM_DB_OWNER_USER = $script:Env.POSTGRES_USER
    PLATFORM_DB_OWNER_PASSWORD = $script:Env.POSTGRES_PASSWORD
    PLATFORM_MIGRATE_ON_START = $null
    PLATFORM_SIGNING_PRIVATE_KEY_FILE = '/run/keys/signing-private.pem'
    PLATFORM_SIGNING_PUBLIC_KEY_FILE = '/run/keys/signing-public.pem'
    PLATFORM_KEYFILE_B64__signing_a_private = $null
    PLATFORM_KEYFILE_B64__signing_a_public = $null
}
$load = Start-Load 'rollback' 12 '2.0'
Start-Sleep -Seconds $Baseline
Set-ReleaseStarted $load
Invoke-RollingRelease -ApiImage $PreviousImage -SkipMigration -Override $override -DockerArguments @('-v', "${secrets}:/run/keys:ro")
Start-Sleep -Seconds $Settle
$result = Stop-Load $load

$after = Get-OwnerSql 'select platform_schema_version()'
Check $result.Passed 'rolling back to the previous image caused no failed request'
Check ($after -eq $schema) "the schema was not changed by the rollback (still version $after)"
$stamps = @((Get-Replicas 'api') | ForEach-Object { Get-Stamp $_ })
Say "Replicas after the rollback: $((Get-Replicas 'api') -join ', ')"
$ready = $true
foreach ($app in (Get-Replicas 'api')) { if ("$(Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/health/readiness)" -notmatch '"status":"UP"') { $ready = $false } }
Check $ready 'every replica of the previous release is ready on the newer schema'
Finish 'rollback: the previous image on the new schema'
