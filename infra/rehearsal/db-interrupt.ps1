<#
 What a database failover does to a running deployment (ADR-0293): the database goes away for a few seconds in the middle of load
 and comes back. A real failover drops every connection and the new primary is reachable at the same address a minute or so later.
 The promise: readiness turns false while the database is gone (so the load balancer sends nothing to an instance that cannot
 work), the connection pool recovers WITHOUT a restart of any instance, readiness is true again soon after, and new requests are
 served normally. Requests made during the outage fail; they are the outage, not the release. What is judged is the recovery.
#>
param([int]$OutageSeconds = 12, [int]$RecoverWithin = 90)
. "$PSScriptRoot/lib.ps1"
Invoke-Seed
$apis = Get-Replicas 'api'
if ($apis.Count -lt 1) { throw 'Start the rehearsal first (./up.ps1).' }
$started = @{}
foreach ($app in $apis) { $started[$app] = (Invoke-DockerQuiet inspect -f '{{.State.StartedAt}}' $app) -join '' }

$load = Start-Load 'outage' 8 '1000'
Start-Sleep -Seconds 10
Say "Stopping the database for $OutageSeconds seconds"
Invoke-Compose stop postgres
$sawDown = $false
$until = (Get-Date).AddSeconds($OutageSeconds)
while ((Get-Date) -lt $until) {
    $answer = "$(Invoke-DockerQuiet exec $apis[0] wget -q -O- http://127.0.0.1:8081/actuator/health/readiness)"
    if ($answer -notmatch '"status":"UP"') { $sawDown = $true }
    Start-Sleep -Milliseconds 700
}
Say 'Starting the database again'
Invoke-Compose start postgres
$began = Get-Date
$recovered = $false
while (((Get-Date) - $began).TotalSeconds -lt $RecoverWithin -and -not $recovered) {
    $all = $true
    foreach ($app in $apis) {
        if ("$(Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/health/readiness)" -notmatch '"status":"UP"') { $all = $false }
    }
    if ($all) { $recovered = $true } else { Start-Sleep -Seconds 1 }
}
$seconds = [int]((Get-Date) - $began).TotalSeconds
$null = Stop-Load $load

Check $sawDown 'readiness turned false while the database was gone'
Check $recovered "readiness was true again on every replica $seconds s after the database came back (limit $RecoverWithin s)"
$restarted = @($apis | Where-Object { ((Invoke-DockerQuiet inspect -f '{{.State.StartedAt}}' $_) -join '') -ne $started[$_] })
Check ($restarted.Count -eq 0) "no instance was restarted: the pool recovered by itself (restarted: $($restarted -join ', '))"

Wait-IngressSees 8
Say 'A fresh load after the recovery'
$after = Start-Load 'after-outage' 8 '2.0'
Start-Sleep -Seconds 20
$result = Stop-Load $after
Check $result.Passed 'after the recovery the load runs with no failed request'
Finish 'database interruption and recovery'
