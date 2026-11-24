<#
 THE EXIT TEST OF SPRINT 33, IN MINIATURE (ADR-0289): a rolling release under live traffic causes no failed requests.

 The load generator signs in and sends a steady mix of reads, writes and page loads through the ingress (~100 a second). After a
 steady-state baseline the release starts: the migration job with the NEW image, then each application replica is replaced (the new
 one starts beside the old ones, the ingress sees it, one old one is stopped and drains), then each web replica. The load keeps
 running until the release is done and a settle time has passed.

 THE PASS RULE (written in ADR-0289 before any number was seen, never moved): zero failed requests (a connection error, a
 timeout, a 5xx or a 401; an answer such as 409 or 429 is an answer, not a failure), and the p95 of the requests made during the
 release at most 2 times the p95 of the steady state before it.

 Usage: ./release.ps1                 (platform-app:next and platform-web:next; build them with ./build-images.ps1 -Tag next)
#>
param([string]$ApiImage = 'platform-app:next', [string]$WebImage = 'platform-web:next', [int]$Baseline = 30, [int]$Settle = 20,
    [string]$Factor = '2.0')
. "$PSScriptRoot/lib.ps1"
foreach ($image in $ApiImage, $WebImage) {
    $null = Invoke-DockerQuiet image inspect $image
    if ($LASTEXITCODE -ne 0) { throw "The image $image does not exist (./build-images.ps1 -Tag next)." }
}
$apis = Get-Replicas 'api'
if ($apis.Count -lt 2) { throw 'Start at least two application replicas first (./up.ps1).' }
Invoke-Seed
$before = @($apis | ForEach-Object { Get-Stamp $_ })
Say "Replicas before: $($apis -join ', ') with stamps $($before -join ', ')"

$load = Start-Load 'release' 12 $Factor
Say "Steady state for $Baseline seconds"
Start-Sleep -Seconds $Baseline
Set-ReleaseStarted $load
Say 'Release started'
$began = Get-Date
Invoke-RollingRelease -ApiImage $ApiImage -WebImage $WebImage
$took = [int]((Get-Date) - $began).TotalSeconds
Say "Release finished in $took seconds; settling for $Settle seconds"
Start-Sleep -Seconds $Settle
$result = Stop-Load $load

$after = @((Get-Replicas 'api') | ForEach-Object { Get-Stamp $_ })
Check $result.Passed 'the rolling release caused no failed request and kept the p95 within the factor of the steady state'
Check ((($after | Select-Object -Unique).Count -eq 1) -and ($after[0] -ne $before[0])) "every replica now carries the new release stamp ($($after -join ', '))"
Check ((Get-Replicas 'api').Count -eq $apis.Count) 'the number of application replicas is what it was'
$longest = if ($script:StopTimes.Count -gt 0) { ($script:StopTimes | Measure-Object -Maximum).Maximum } else { 0 }
Check ($longest -lt 80) "no old replica needed to be killed: the longest stop took $longest s (the stop timeout is 90)"
Finish 'rolling release under load'
