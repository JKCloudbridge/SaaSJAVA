<#
.SYNOPSIS
  Runs the limits and webhook benchmark (Sprint 32, ADR-0285) against a running application.

.DESCRIPTION
  A wrapper round LimitsLoad.java (run by the JDK). The thresholds L1 to L4 and W1 to W4 are those of ADR-0285, written before
  any number was seen and never changed after one is. The script is NOT part of the Maven build and NOT run by CI.
  -Size smoke proves the harness runs with tiny numbers and is NOT a measurement. The exit code is 0 when every judged
  threshold was met, 2 when one was not.

  Needs the JDK on the path and a running application (the local profile is enough) with a connected application's token or a
  person's access token for an organization that has the plan feature. For L1 and W3 run three instances behind the ports named in
  -Bases, sharing one Redis. L3 pauses the container named in -RedisContainer.

.EXAMPLE
  .\benchmark\limits\run-limits-benchmark.ps1 -Size smoke -OrganizationHost tenant-a.localhost -Token $env:LIMITS_TOKEN
#>
param(
    [ValidateSet('smoke', 'full')][string]$Size = 'full',
    [ValidateSet('limits', 'webhooks', 'both')][string]$Mode = 'both',
    [string]$Bases = 'http://127.0.0.1:8080',
    [Parameter(Mandatory = $true)][string]$OrganizationHost,
    [string]$Token = $env:LIMITS_TOKEN,
    [string]$OrganizationHost2,
    [string]$Token2 = $env:LIMITS_TOKEN2,
    [string]$RedisContainer,
    [string]$KillCommand,
    [string]$Object = 'Employee__c',
    [int]$Seconds = 60
)

$ErrorActionPreference = 'Stop'
if (-not $Token) {
    throw 'Give -Token (or set LIMITS_TOKEN): the token of a connected application or of a signed-in person.'
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path (Split-Path -Parent $here) ("results\{0}-limits-{1}" -f (Get-Date -Format 'yyyyMMdd-HHmmss'), $Size)
New-Item -ItemType Directory -Force $out | Out-Null

$common = @("--size=$Size", "--bases=$Bases", "--host=$OrganizationHost", "--token=$Token", "--seconds=$Seconds", "--object=$Object")
if ($OrganizationHost2) { $common += @("--host2=$OrganizationHost2", "--token2=$Token2") }
$summary = @("# Limits and webhook benchmark ($Size)", '', "Run at $(Get-Date -Format s). Thresholds: ADR-0285.", '')
$worst = 0

function Invoke-Part([string]$name, [string[]]$extra) {
    $log = Join-Path $out "$name.txt"
    & java (Join-Path $here 'LimitsLoad.java') "--mode=$name" @common @extra 2>&1 | Tee-Object -FilePath $log
    $script:worst = [Math]::Max($script:worst, $LASTEXITCODE)
    $script:summary += "## $name"
    $script:summary += ''
    $script:summary += (Get-Content $log | Where-Object { $_ -match '^(L|W)[0-9] ' -or $_ -match 'RUN|Judged' })
    $script:summary += ''
}

if ($Mode -in 'limits', 'both') {
    $extra = @()
    if ($RedisContainer) { $extra += "--redis-container=$RedisContainer" }
    Invoke-Part 'limits' $extra
}
if ($Mode -in 'webhooks', 'both') {
    $extra = @()
    if ($KillCommand) { $extra += "--kill-command=$KillCommand" }
    Invoke-Part 'webhooks' $extra
}

$summary | Set-Content -Path (Join-Path $out 'summary.md') -Encoding utf8
Write-Host "Written to $out"
exit $worst
