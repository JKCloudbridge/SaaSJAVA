<#
 Shared by every script of the rehearsal (Sprint 33). Dot-source it: `. "$PSScriptRoot/lib.ps1"`.
 It starts, stops and replaces the application and web replicas the way a deployment does (an application container with its
 edge sidecar beside it, as in a task), runs the migration job, the load generator and the checks, and prints PASS or FAIL.
 Nothing here is a secret: the credentials come from the ignored .env that init.ps1 made, and only ever live on this machine.
#>
$ErrorActionPreference = 'Stop'

$script:Here = $PSScriptRoot
$script:Run = Join-Path $script:Here '.run'
New-Item -ItemType Directory -Force $script:Run | Out-Null
$script:Network = 'platform-rehearsal'
$script:Subnet = '172.31.77.0/24'
# The edge sidecars trust ONE address: the ingress, as a sidecar in a task trusts the balancer's network and not the callers'.
$script:TrustedHop = '172.31.77.250/32'
$script:Failures = 0
$script:Organization = 'tenant-a'

function Read-DotEnv {
    $values = @{}
    $file = Join-Path $script:Here '.env'
    if (-not (Test-Path $file)) { throw 'Run ./init.ps1 first: there is no .env.' }
    foreach ($line in Get-Content $file) {
        if ($line -match '^\s*([A-Za-z0-9_]+)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}
$script:Env = Read-DotEnv
$script:Domain = $script:Env.BASE_DOMAIN

# ---- output ----
function Say([string]$text) { Write-Host $text }
function Pass([string]$text) { Write-Host "PASS: $text" -ForegroundColor Green }
function Fail([string]$text) { Write-Host "FAIL: $text" -ForegroundColor Red; $script:Failures++ }
function Check([bool]$condition, [string]$text) { if ($condition) { Pass $text } else { Fail $text } }
function Finish([string]$name) {
    if ($script:Failures -gt 0) { Write-Host "RESULT FAIL: $name ($($script:Failures) failed checks)" -ForegroundColor Red; exit 2 }
    Write-Host "RESULT PASS: $name" -ForegroundColor Green
    exit 0
}

# ---- docker ----
function Invoke-Docker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw "docker $($args -join ' ') failed ($LASTEXITCODE)" }
}
function Invoke-DockerQuiet {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $output = & docker @args 2>&1 } finally { $ErrorActionPreference = $previous }
    return $output
}
function Invoke-Compose {
    Push-Location $script:Here
    try {
        & docker compose @args
        if ($LASTEXITCODE -ne 0) { throw "docker compose $($args -join ' ') failed ($LASTEXITCODE)" }
    } finally { Pop-Location }
}

# ---- names and certificate ----
function Set-Names {
    $hosts = @("127.0.0.1 $($script:Domain)", "127.0.0.1 $($script:Organization).$($script:Domain)")
    Set-Content -Path (Join-Path $script:Run 'hosts.txt') -Value $hosts -Encoding ascii
}
function Get-RootCertificate {
    $root = Join-Path $script:Run 'root.crt'
    if (-not (Test-Path $root)) {
        Invoke-Compose cp ingress:/data/caddy/pki/authorities/local/root.crt $root | Out-Null
    }
    return $root
}
function Invoke-Curl {
    $root = Get-RootCertificate
    $resolve = @('--resolve', "$($script:Domain):443:127.0.0.1", '--resolve', "$($script:Organization).$($script:Domain):443:127.0.0.1",
        '--resolve', "$($script:Domain):80:127.0.0.1", '--resolve', "$($script:Organization).$($script:Domain):80:127.0.0.1")
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $output = & curl.exe -sS --ssl-no-revoke --max-time 60 --cacert $root @resolve @args 2>&1 } finally { $ErrorActionPreference = $previous }
    return $output
}

# ---- replicas ----
function Get-ApiEnv([hashtable]$Override) {
    $e = [ordered]@{}
    $e['PLATFORM_ENVIRONMENT'] = 'rehearsal'
    $e['PLATFORM_HTTP_PORT'] = '8090'
    $e['PLATFORM_DB_URL'] = "jdbc:postgresql://postgres:5432/$($script:Env.POSTGRES_DB)"
    $e['PLATFORM_DB_APP_USER'] = 'platform_app'
    $e['PLATFORM_DB_APP_PASSWORD'] = $script:Env.APP_DB_PASSWORD
    # The migration job ran first; a replica only checks the schema, and never holds the owner's password (ADR-0288).
    $e['PLATFORM_MIGRATE_ON_START'] = 'false'
    $e['PLATFORM_BASE_DOMAIN'] = $script:Domain
    $e['PLATFORM_PUBLIC_BASE_URL'] = "https://$($script:Domain)"
    # The ingress appends to X-Forwarded-For and passes X-Forwarded-Host through, like a cloud balancer (ADR-0286).
    $e['PLATFORM_TRUST_FORWARDED_HOST'] = 'false'
    $e['PLATFORM_TRUST_FORWARDED_FOR'] = 'true'
    $e['PLATFORM_FORWARDED_FOR_HOPS'] = '1'
    $e['PLATFORM_REDIS_HOST'] = 'redis'
    $e['PLATFORM_REDIS_PASSWORD'] = $script:Env.REDIS_PASSWORD
    $e['PLATFORM_SMTP_HOST'] = 'mail-catcher'
    $e['PLATFORM_SMTP_PORT'] = '1025'
    $e['PLATFORM_SMTP_AUTH'] = 'false'
    $e['PLATFORM_SMTP_STARTTLS_REQUIRED'] = 'false'
    $e['PLATFORM_MAIL_SENDER'] = "no-reply@$($script:Domain)"
    $e['PLATFORM_SIGNING_KEY_ID'] = 'rehearsal-1'
    $e['PLATFORM_SIGNING_PRIVATE_KEY_FILE'] = '/tmp/platform-keys/signing_a_private'
    $e['PLATFORM_SIGNING_PUBLIC_KEY_FILE'] = '/tmp/platform-keys/signing_a_public'
    $keys = Join-Path $script:Here 'secrets'
    $e['PLATFORM_KEYFILE_B64__signing_a_private'] = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $keys 'signing-private.pem')))
    $e['PLATFORM_KEYFILE_B64__signing_a_public'] = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $keys 'signing-public.pem')))
    $e['PLATFORM_SECRETS_KEK_ACTIVE_ID'] = 'rehearsal-1'
    $e['PLATFORM_SECRETS_KEK_ACTIVE_KEY'] = $script:Env.SECRETS_KEK
    $e['PLATFORM_STORAGE_ENDPOINT'] = 'http://object-storage:8333'
    $e['PLATFORM_STORAGE_PATH_STYLE'] = 'true'
    $e['PLATFORM_STORAGE_BUCKET'] = 'platform-files'
    $e['PLATFORM_STORAGE_ACCESS_KEY'] = $script:Env.S3_ACCESS_KEY
    $e['PLATFORM_STORAGE_SECRET_KEY'] = $script:Env.S3_SECRET_KEY
    foreach ($name in $Override.Keys) {
        if ($null -eq $Override[$name]) { $e.Remove($name) } else { $e[$name] = [string]$Override[$name] }
    }
    $arguments = @()
    foreach ($name in $e.Keys) { $arguments += '-e'; $arguments += "$name=$($e[$name])" }
    return , $arguments
}

function Get-Replicas([string]$Kind) {
    $names = Invoke-DockerQuiet ps --filter "name=rehearsal-$Kind-" --format '{{.Names}}'
    return @($names | Where-Object { $_ -match "^rehearsal-$Kind-\d+$" } | Sort-Object { [int]($_ -replace '\D', '') })
}
function Get-NextIndex([string]$Kind) {
    $all = Invoke-DockerQuiet ps -a --filter "name=rehearsal-$Kind-" --format '{{.Names}}'
    $numbers = @($all | Where-Object { $_ -match "^rehearsal-$Kind-\d+$" } | ForEach-Object { [int]($_ -replace '\D', '') })
    if ($numbers.Count -eq 0) { return 1 }
    return (($numbers | Measure-Object -Maximum).Maximum + 1)
}

function Start-Api([int]$n, [string]$image, [hashtable]$override = @{}, [string[]]$dockerArguments = @()) {
    $app = "rehearsal-api-$n"
    $edge = "rehearsal-edge-$n"
    $arguments = Get-ApiEnv $override
    Invoke-Docker run -d --name $app --network $script:Network --network-alias "backend-$(Get-FreeSlot 'api')" --stop-timeout 90 @dockerArguments @arguments $image | Out-Null
    Invoke-Docker run -d --name $edge --network "container:$app" -e "TRUSTED_CIDR=$($script:TrustedHop)" --stop-timeout 30 platform-edge:local | Out-Null
    return $app
}

function Start-Web([int]$n, [string]$image) {
    $name = "rehearsal-web-$n"
    Invoke-Docker run -d --name $name --network $script:Network --network-alias "web-$(Get-FreeSlot 'web')" -e WEB_SHUTDOWN_DELAY=10 --stop-timeout 30 $image | Out-Null
    return $name
}

function Wait-ApiReady([string]$app, [int]$seconds = 180) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        $answer = Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/health/readiness
        if ("$answer" -match '"status":"UP"') { return $true }
        $state = Invoke-DockerQuiet inspect -f '{{.State.Running}}' $app
        if ("$state" -ne 'true') { Say (Invoke-DockerQuiet logs --tail 40 $app | Out-String); throw "$app stopped while starting" }
        Start-Sleep -Seconds 2
    }
    Say (Invoke-DockerQuiet logs --tail 60 $app | Out-String)
    throw "$app was not ready within $seconds seconds"
}

function Wait-WebReady([string]$web, [int]$seconds = 60) {
    $until = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $until) {
        $answer = Invoke-DockerQuiet exec $web wget -q -O- http://127.0.0.1:3000/healthz
        if ("$answer" -match 'ok') { return }
        Start-Sleep -Seconds 1
    }
    throw "$web was not ready within $seconds seconds"
}

# The ingress checks health every second, needs two... one pass in the rehearsal's file; give it a few seconds to see a new replica.
function Wait-IngressSees([int]$seconds = 6) { Start-Sleep -Seconds $seconds }

function Stop-Api([string]$app) {
    $n = $app -replace '\D', ''
    $edge = "rehearsal-edge-$n"
    $began = Get-Date
    # The application first (it says "not ready", keeps serving, drains), the sidecar after it, as in a task (ADR-0289).
    Invoke-DockerQuiet stop -t 90 $app | Out-Null
    $took = [int]((Get-Date) - $began).TotalSeconds
    Invoke-DockerQuiet stop -t 30 $edge | Out-Null
    Invoke-DockerQuiet rm $edge | Out-Null
    Invoke-DockerQuiet rm $app | Out-Null
    return $took
}

function Stop-Web([string]$web) {
    $began = Get-Date
    Invoke-DockerQuiet stop -t 30 $web | Out-Null
    Invoke-DockerQuiet rm $web | Out-Null
    return [int]((Get-Date) - $began).TotalSeconds
}

function Get-Stamp([string]$app) {
    $info = Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/info
    if ("$info" -match '"commit":"([^"]+)"') { return $Matches[1] }
    return 'unknown'
}

# ---- the migration job ----
function Invoke-MigrationJob([string]$image) {
    $env:MIGRATE_IMAGE = $image
    try { Invoke-Compose --profile jobs run --rm migrate } finally { Remove-Item Env:MIGRATE_IMAGE -ErrorAction SilentlyContinue }
}

# ---- the rolling release (the one procedure that release, rollback and rotations all use) ----
$script:StopTimes = @()
function Invoke-RollingRelease {
    param([string]$ApiImage, [string]$WebImage = '', [hashtable]$Override = @{}, [string[]]$DockerArguments = @(),
        [switch]$SkipMigration)
    if (-not $SkipMigration) {
        Say "Migration job with $ApiImage"
        Invoke-MigrationJob $ApiImage
    }
    $oldApis = Get-Replicas 'api'
    foreach ($old in $oldApis) {
        # Start the new one beside the old ones, wait until it is ready and the ingress sees it, then stop one old one: the
        # capacity never falls below what is running (ADR-0289).
        $n = Get-NextIndex 'api'
        $new = Start-Api $n $ApiImage $Override $DockerArguments
        Wait-ApiReady $new | Out-Null
        Wait-IngressSees
        $took = Stop-Api $old
        $script:StopTimes += $took
        Say "  replaced $old by $new (the old one took $took s to stop)"
    }
    if ($WebImage -ne '') {
        foreach ($old in (Get-Replicas 'web')) {
            $n = Get-NextIndex 'web'
            $new = Start-Web $n $WebImage
            Wait-WebReady $new
            Wait-IngressSees
            $took = Stop-Web $old
            Say "  replaced $old by $new (the old one took $took s to stop)"
        }
    }
}

# ---- the load generator ----
function Get-JavaArguments([string[]]$extra) {
    $load = Join-Path $script:Here 'load/ReleaseLoad.java'
    $hosts = Join-Path $script:Run 'hosts.txt'
    $root = Get-RootCertificate
    $state = Join-Path $script:Run 'state.txt'
    return @("-Djdk.net.hosts.file=$hosts", $load, "--root=$root", "--state=$state") + $extra
}

function Invoke-Seed {
    $state = Join-Path $script:Run 'state.txt'
    if (Test-Path $state) { return }
    Set-Names
    $arguments = Get-JavaArguments @('--mode=seed', "--platform=$($script:Domain)", "--organization=$($script:Organization)")
    & java @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Seeding the rehearsal organization failed.' }
}

function Start-Load([string]$name, [int]$workers = 12, [string]$factor = '2.0', [int]$refresh = 0) {
    Set-Names
    Stop-StrayLoads
    Reset-Limits
    $control = Join-Path $script:Run "control-$name"
    if (Test-Path $control) { Remove-Item -Recurse -Force $control }
    New-Item -ItemType Directory -Force $control | Out-Null
    $out = Join-Path $script:Run "$name.out"
    $err = Join-Path $script:Run "$name.err"
    $arguments = Get-JavaArguments @('--mode=load', "--control=$control", "--workers=$workers", "--factor=$factor", "--refresh=$refresh", "--out=$out.result")
    # Start-Process joins its arguments with spaces and does not quote them, and this folder's path has spaces in it.
    $arguments = @($arguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } })
    $process = Start-Process -FilePath 'java' -ArgumentList $arguments -PassThru -NoNewWindow `
        -RedirectStandardOutput $out -RedirectStandardError $err
    # Wait until it has signed in and is sending.
    $until = (Get-Date).AddSeconds(90)
    while ((Get-Date) -lt $until) {
        if (Test-Path $out) { if ((Get-Content $out -Raw) -match 'LOAD started') { break } }
        if ($process.HasExited) { throw "The load generator stopped at once: $(Get-Content $err -Raw)" }
        Start-Sleep -Milliseconds 500
    }
    return @{ Process = $process; Control = $control; Out = $out; Result = "$out.result"; Name = $name }
}

function Set-ReleaseStarted($load) { New-Item -ItemType File -Force (Join-Path $load.Control 'release-started') | Out-Null }

function Stop-Load($load) {
    New-Item -ItemType File -Force (Join-Path $load.Control 'stop') | Out-Null
    if (-not $load.Process.WaitForExit(180000)) { $load.Process.Kill(); throw 'The load generator did not stop.' }
    $text = if (Test-Path $load.Result) { Get-Content $load.Result -Raw } else { Get-Content $load.Out -Raw }
    Say ($text.TrimEnd())
    return @{ Passed = ($text -match 'RESULT PASS'); Text = $text }
}

# ---- facts about the load result ----
function Get-ResultNumber([string]$text, [string]$name) {
    if ($text -match "$name=(\d+)") { return [int]$Matches[1] }
    return -1
}

function Get-OwnerSql([string]$sql) {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $answer = & docker compose --project-directory $script:Here exec -T postgres psql -U $script:Env.POSTGRES_USER -d $script:Env.POSTGRES_DB -v ON_ERROR_STOP=1 -tA -c $sql 2>&1 } finally { $ErrorActionPreference = $previous }
    return "$answer".Trim()
}

# PowerShell 5.1 strips the quotes inside an argument it hands to a native program, so a JSON body goes by file.
function New-JsonFile([string]$text) {
    $file = Join-Path $script:Run ("body-" + [guid]::NewGuid().ToString('N') + '.json')
    [IO.File]::WriteAllText($file, $text, (New-Object Text.UTF8Encoding($false)))
    return $file
}
function Get-OrganizationToken {
    $arguments = Get-JavaArguments @('--mode=token')
    $answer = (& java @arguments 2>&1) -join "`n"
    if ($answer -match 'TOKEN (\S+)') { return $Matches[1] }
    throw "Could not sign in: $answer"
}

# The sign-in limits are counted per source address, and every script here signs in from this one machine. A run starts from
# clean counters (the rehearsal's own throw-away Redis), so what an earlier run did to the allowance does not decide this one.
function Reset-Limits {
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & docker compose --project-directory $script:Here exec -T redis redis-cli -a $script:Env.REDIS_PASSWORD --no-auth-warning flushall 2>&1 | Out-Null } finally { $ErrorActionPreference = $previous }
}

# A replica takes the lowest free SLOT (1 to 8) as its network alias (backend-<slot>, web-<slot>): the ingress health-checks the
# slots by name and sends requests only to one that passes, as a balancer registers a target only when it is healthy.
function Get-FreeSlot([string]$kind) {
    $alias = if ($kind -eq 'api') { 'backend' } else { 'web' }
    $used = @()
    foreach ($name in (Get-Replicas $kind)) {
        $aliases = (Invoke-DockerQuiet inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{range $v.Aliases}}{{println .}}{{end}}{{end}}' $name) -join ' '
        foreach ($m in [regex]::Matches($aliases, "$alias-(\d+)")) { $used += [int]$m.Groups[1].Value }
    }
    foreach ($slot in 1..8) { if ($used -notcontains $slot) { return $slot } }
    throw "All eight $kind slots are in use."
}

# A load generator that an earlier, aborted run left alive would keep sending with a token that expires and would overwrite the
# result of the next run. Every run starts by ending any that is left.
function Stop-StrayLoads {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'ReleaseLoad' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
