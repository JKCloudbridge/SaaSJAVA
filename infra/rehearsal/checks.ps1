<#
 What the ingress and the edge must do, proved against the running rehearsal (Sprint 33, ADR-0286). Each check prints PASS or
 FAIL; the exit code is 0 only when every one passed. Run after ./up.ps1.

   1  https works, http is sent to https, HSTS is sent
   2  nothing but the ingress (and the mail catcher's screen) is published: the management port cannot be reached
   3  /actuator answers 404 through the ingress
   4  a FORGED X-Forwarded-Host changes nothing: the organization is the one the Host header names
   5  a FORGED X-Forwarded-For does not choose the caller's address: the per-source limit still bites
   6  the size limit of every kind of request holds, for an announced length and for a body with no length
   7  the application refuses to start on a schema that is behind its release, with one clear sentence
#>
. "$PSScriptRoot/lib.ps1"
Set-Names
Reset-Limits
$org = "https://$($script:Organization).$($script:Domain)"
$platform = "https://$($script:Domain)"
# Signed in BEFORE the burst of check 5: that burst uses up this machine's per-source sign-in allowance, as it should.
$token = Get-OrganizationToken

# 1
$headers = Invoke-Curl -I "$platform/healthz"
Check ("$headers" -match '(?i)strict-transport-security: max-age=31536000') 'https answers with HSTS'
$redirect = Invoke-Curl -I "http://$($script:Domain)/"
Check ("$redirect" -match '30[78]' -and "$redirect" -match "(?i)location: https://") 'http is sent to https'
Check ("$headers" -notmatch '(?i)^server:') 'the server name is not shown'

# 2
$published = (Invoke-DockerQuiet ps --format '{{.Names}} {{.Ports}}') -join "`n"
$leaks = @($published -split "`n" | Where-Object { $_ -match '^rehearsal-(api|edge|web)-' -and $_ -match '->' })
Check ($leaks.Count -eq 0) 'no application, sidecar or web container publishes a port (the management port 8081 is unreachable)'
foreach ($port in 8080, 8081, 8090, 3000) {
    $reach = Invoke-Curl -o NUL -w '%{http_code}' --connect-timeout 2 "http://127.0.0.1:$port/actuator/health"
    Check ("$reach" -in '000', '' -or "$reach" -match 'refused|Failed') "port $port is not reachable from the host"
}

# 3
$code = Invoke-Curl -o NUL -w '%{http_code}' "$platform/actuator/health"
Check ("$code" -eq '404') "/actuator through the ingress answers 404 (got $code)"
$code = Invoke-Curl -o NUL -w '%{http_code}' "$org/actuator/health/readiness"
Check ("$code" -eq '404') "/actuator/health/readiness through the ingress answers 404 (got $code)"

# 4
$plain = Invoke-Curl "$org/api/v1/tenant/current"
Check ("$plain" -match '"displayName"') 'the organization is served by its own host'
$forged = Invoke-Curl -H "X-Forwarded-Host: tenant-b.$($script:Domain)" "$org/api/v1/tenant/current"
Check ("$forged" -match '"displayName"' -and "$forged" -notmatch 'NOT_FOUND') 'a forged X-Forwarded-Host does not change the organization'
$forged2 = Invoke-Curl -H "X-Forwarded-Host: $($script:Domain)" "$org/api/v1/tenant/current"
Check ("$forged2" -match '"displayName"') 'a forged X-Forwarded-Host naming the platform host does not change it either'

# 5: 75 sign-in attempts for unknown addresses from this one machine, each with another forged first entry. If the application
# believed the first entry each would be a new source and none would ever be limited; it takes the address the balancer added.
$jar = Join-Path $script:Run 'xsrf.txt'
$null = Invoke-Curl -c $jar "$platform/api/v1/auth/csrf" -o NUL
$xsrf = ((Get-Content $jar | Where-Object { $_ -match 'XSRF-TOKEN' }) -split "`t")[-1]
$limited = 0
$serverErrors = 0
for ($i = 1; $i -le 75; $i++) {
    $body = New-JsonFile ('{"email":"nobody-' + $i + '-' + [guid]::NewGuid().ToString('N').Substring(0,8) + '@example.test","password":"x"}')
    $answer = Invoke-Curl -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "Cookie: XSRF-TOKEN=$xsrf" -H "X-XSRF-TOKEN: $xsrf" -H "X-Forwarded-For: 203.0.113.$i" --data-binary "@$body" "$platform/api/v1/auth/sign-in"
    Remove-Item $body
    if ("$answer" -eq '429') { $limited++ }
    if ("$answer" -match '^5') { $serverErrors++ }
}
Check ($limited -ge 1) "a forged X-Forwarded-For does not choose the source: $limited of 75 attempts were limited"
Check ($serverErrors -eq 0) 'the sign-in endpoint gave no server error under the burst'

# 6: sizes. The classes are those of deploy/edge/api-rules.caddy. Two proofs.
#    (a) Through the whole chain an oversized body is never accepted: the answer is a refusal, never a success or a server error
#        (the application may refuse early, before the edge has to read anything).
#    (b) The edge's own limits, directly: the real edge image in front of a stub that reads every byte it is sent. Just under the
#        limit the stub receives all of it; just over it, and for a body with no announced length, the edge answers 413.
function New-BigFile([int]$megabytes) {
    $file = Join-Path $script:Run "big-$megabytes.bin"
    if (-not (Test-Path $file)) {
        $stream = [IO.File]::Create($file)
        $stream.SetLength([int64]$megabytes * 1MB)
        $stream.Close()
    }
    return $file
}
$classes = @(
    @{ Name = 'a record'; Path = '/api/v1/objects/Load__c/records'; Limit = 2 },
    @{ Name = 'the public API'; Path = '/api/public/v1/objects/Load__c/records'; Limit = 2 },
    @{ Name = 'the inbound receiver'; Path = '/api/v1/inbound/none/none'; Limit = 2 },
    @{ Name = 'a spreadsheet upload'; Path = '/api/v1/sheets/Load__c/00000000-0000-0000-0000-000000000000/versions/import'; Limit = 6 },
    @{ Name = 'bulk'; Path = '/api/v1/objects/Load__c/records/bulk'; Limit = 9 },
    @{ Name = 'a file on a record'; Path = '/api/v1/objects/Load__c/records/00000000-0000-0000-0000-000000000000/files'; Limit = 27 },
    @{ Name = 'an import'; Path = '/api/v1/objects/Load__c/imports'; Limit = 30 }
)
foreach ($class in $classes) {
    $file = New-BigFile ($class.Limit + 1)
    $answer = "$(Invoke-Curl -o NUL -w '%{http_code}' -X POST -H "Authorization: Bearer $token" -H 'Content-Type: application/octet-stream' --data-binary "@$file" "$org$($class.Path)")"
    Check ($answer -notmatch '^2' -and $answer -notmatch '(?<!\d)5\d\d(?!\d)') "through the chain, $($class.Name): $($class.Limit + 1) MB is not accepted (answer: $answer)"
}
$small = Invoke-Curl -o NUL -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary "@$(New-JsonFile '{"fields":{}}')" "$org/api/v1/objects/Load__c/records"
Check ("$small" -in '401', '403') "a small unauthenticated write reaches the application and is refused there (got $small)"

$null = Invoke-DockerQuiet rm -f rehearsal-stub-edge rehearsal-stub
# (a file, because PowerShell 5.1 strips the quotes inside an argument it hands to a native program)
$stubFile = Join-Path $script:Run 'stub.js'
[IO.File]::WriteAllText($stubFile, 'require("http").createServer((q,s)=>{let n=0;q.on("data",d=>n+=d.length);q.on("end",()=>s.end(String(n)))}).listen(8090,"127.0.0.1")', (New-Object Text.UTF8Encoding($false)))
Invoke-Docker run -d --name rehearsal-stub --network $script:Network -v "$($script:Run):/stub:ro" --entrypoint node platform-web:local /stub/stub.js | Out-Null
Invoke-Docker run -d --name rehearsal-stub-edge --network container:rehearsal-stub -e "TRUSTED_CIDR=$($script:TrustedHop)" platform-edge:local | Out-Null
Start-Sleep -Seconds 4
function Send-ToEdge([string]$path, [int]$megabytes, [bool]$chunked) {
    $bytes = [int64]$megabytes * 1000000
    $upload = if ($chunked) { "-X POST -H 'Transfer-Encoding: chunked' -T -" } else { "-X POST --data-binary @-" }
    $command = "head -c $bytes /dev/zero | curl -s -w ' [%{http_code}]' -H 'Content-Type: application/octet-stream' $upload http://rehearsal-stub:8080$path"
    return "$(Invoke-DockerQuiet run --rm --network $script:Network --entrypoint sh curlimages/curl -c $command)"
}
foreach ($class in $classes) {
    $under = Send-ToEdge $class.Path ($class.Limit - 1) $false
    Check ($under -match '\[200\]' -and $under -match [string](([int64]($class.Limit - 1)) * 1000000)) "edge, $($class.Name): $($class.Limit - 1) MB goes through whole"
    $over = Send-ToEdge $class.Path ($class.Limit + 1) $false
    Check ($over -match '\[413\]') "edge, $($class.Name): $($class.Limit + 1) MB with an announced length is refused with 413 (got: $over)"
    $chunked = Send-ToEdge $class.Path ($class.Limit + 1) $true
    Check ($chunked -match '\[413\]' -or $chunked -notmatch '\[200\]') "edge, $($class.Name): $($class.Limit + 1) MB with NO announced length is not accepted whole (got: $chunked)"
}
$null = Invoke-DockerQuiet rm -f rehearsal-stub-edge rehearsal-stub

# 7: a schema that is behind. A throw-away database at an older version (the owner role makes it) and an application started on
# it with the checks on and without the right to migrate.
$name = "behind_$([guid]::NewGuid().ToString('N').Substring(0,8))"
Get-OwnerSql "create database $name" | Out-Null
Get-OwnerSql "grant connect on database $name to platform_app" | Out-Null
$arguments = Get-ApiEnv @{ PLATFORM_DB_URL = "jdbc:postgresql://postgres:5432/$name"; PLATFORM_HTTP_PORT = '8092'; PLATFORM_MANAGEMENT_PORT = '8093' }
$logs = Invoke-DockerQuiet run --rm --name rehearsal-behind --network $script:Network @arguments platform-app:local
$text = $logs -join "`n"
Check ($text -match 'schema is behind this release') 'an application started on a schema that is behind its release refuses, in one clear sentence'
Check ($text -notmatch 'jdbc:|password') 'the refusal names no address and no password'
Get-OwnerSql "drop database $name" | Out-Null

Finish 'ingress and edge checks'
