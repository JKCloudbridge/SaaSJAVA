<#
 Horizontal scaling check (Sprint 33, ADR-0289): the same checks with 1, 2 and 3 application replicas. At each size:
   - a load of reads and writes through the ingress ends with no failed request;
   - every replica really served requests (the load is spread; a replica that took none is found out);
   - a sign-in, whose steps may each reach another replica, works every time (sessions and tokens do not live in one instance);
   - at 3 replicas the per-source sign-in limit is SHARED (through Redis): about the same number of attempts get through as at 1
     replica, not three times as many.
 What cannot be seen from outside (the security cache with three real instances, the outbox and the workflow dispatcher on
 several instances, the approval timers, a worker that crashes) is proved by the integration tests that start real instances
 beside each other: SecurityCacheIT, SecurityCacheConsistencyIT, WorkerCrashIT, OrderlyStopIT, FairShareIT, MultiInstanceIT.
#>
. "$PSScriptRoot/lib.ps1"
Invoke-Seed
Set-Names
$org = "https://$($script:Organization).$($script:Domain)"
$platform = "https://$($script:Domain)"

function Set-Replicas([int]$count) {
    $current = Get-Replicas 'api'
    while ($current.Count -lt $count) {
        $new = Start-Api (Get-NextIndex 'api') 'platform-app:local'
        Wait-ApiReady $new | Out-Null
        $current = Get-Replicas 'api'
    }
    while ($current.Count -gt $count) {
        Stop-Api $current[0] | Out-Null
        $current = Get-Replicas 'api'
    }
    Wait-IngressSees 8
}

function Get-Served([string]$app) {
    $text = (Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/prometheus) -join "`n"
    $total = 0.0
    foreach ($m in [regex]::Matches($text, '(?m)^http_server_requests_seconds_count\{[^}]*\}\s+([0-9.eE+-]+)')) { $total += [double]$m.Groups[1].Value }
    return $total
}

function Get-AttemptsThroughTheLimit([int]$attempts) {
    $jar = Join-Path $script:Run 'xsrf-scale.txt'
    $null = Invoke-Curl -c $jar "$platform/api/v1/auth/csrf" -o NUL
    $xsrf = ((Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' }) -split "`t")[-1]
    $through = 0
    for ($i = 1; $i -le $attempts; $i++) {
        $body = New-JsonFile ('{"email":"nobody-' + $i + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '@example.test","password":"x"}')
        $answer = Invoke-Curl -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" `
            --data-binary "@$body" "$platform/api/v1/auth/sign-in"
        Remove-Item $body
        # Only an answer that was counted as an attempt (401, or 429 once the limit is reached) says anything about the limit.
        if ("$answer" -eq '401') { $through++ }
    }
    return $through
}

$passed = @{}
foreach ($size in 1, 2, 3) {
    Say "---- $size application replica(s) ----"
    Set-Replicas $size
    $replicas = Get-Replicas 'api'
    $before = @{}
    foreach ($app in $replicas) { $before[$app] = Get-Served $app }

    $load = Start-Load "scale-$size" 12 '2.0'
    $signIns = 0
    $signInFailures = 0
    $until = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $until) {
        $arguments = Get-JavaArguments @('--mode=signin')
        $answer = & java @arguments 2>&1
        if ("$answer" -match 'SIGNED-IN') { $signIns++ } else { $signInFailures++ }
    }
    $result = Stop-Load $load
    Check $result.Passed "with $size replica(s) the load ended with no failed request"
    Check ($signInFailures -eq 0 -and $signIns -ge 3) "with $size replica(s) $signIns sign-ins worked, each step possibly on another replica ($signInFailures failed)"
    if ($size -ge 2) {
        $idle = @($replicas | Where-Object { ((Get-Served $_) - $before[$_]) -lt 50 })
        Check ($idle.Count -eq 0) "with $size replicas every replica served requests (idle: $($idle -join ', '))"
    }
    if ($size -eq 1 -or $size -eq 3) {
        Say 'Waiting for the one-minute window of the sign-in limit to pass'
        Start-Sleep -Seconds 65
        $through = Get-AttemptsThroughTheLimit 150
        $passed[$size] = $through
        Say "  $through of 150 attempts got through the per-source limit with $size replica(s)"
    }
}
$one = $passed[1]
$three = $passed[3]
Check ($three -le ($one + 15)) "the limit is shared by the replicas: $three attempts through at 3 replicas against $one at 1 (a per-instance limit would let about three times as many through)"
Set-Replicas 2
Finish 'horizontal scaling at 1, 2 and 3 replicas'
