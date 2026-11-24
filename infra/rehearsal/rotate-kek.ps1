<#
 Replacing the key-encryption key of the secrets store (ADR-0290, runbooks/kek-rotation.md), the procedure of runbooks/secrets.md:
   1  a credential is written under the first key
   2  a rolling release gives the second key as ACTIVE and the first under PREVIOUS (everything opens; new versions use the second)
   3  the credential is written again until no version needs the first key (a version is also moved the first time it is used)
   4  a rolling release removes the first key: the instances start without it
   5  the credential can still be written and listed
 The load runs throughout; no request may fail. Nothing prints a credential's value or a key.
#>
. "$PSScriptRoot/lib.ps1"
Invoke-Seed
Set-Names
$org = "https://$($script:Organization).$($script:Domain)"

function Get-Token {
    $arguments = Get-JavaArguments @('--mode=token')
    $answer = (& java @arguments 2>&1) -join "`n"
    if ($answer -match 'TOKEN (\S+)') { return $Matches[1] }
    throw "Could not sign in: $answer"
}
function Write-Credential([string]$token, [string]$value) {
    $body = Join-Path $script:Run 'credential.json'
    Set-Content -Path $body -Value "{`"value`":`"$value`"}" -Encoding ascii
    return "$(Invoke-Curl -o NUL -w '%{http_code}' -X PUT -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data-binary "@$body" "$org/api/v1/credentials/rehearsal-cred")"
}
function Get-KeyUse { Get-OwnerSql 'select key_id || chr(58) || count(*) from secret_version group by key_id order by key_id' }

$token = Get-Token
$first = Write-Credential $token ('first-' + [guid]::NewGuid().ToString('N'))
Check ($first -in '200', '201') "a credential is written ($first)"
Check ((Get-KeyUse) -match 'rehearsal-1:\d+') 'it is wrapped by the first key'

$newKey = [Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Maximum 256) }))
$both = @{
    PLATFORM_SECRETS_KEK_ACTIVE_ID = 'rehearsal-2'; PLATFORM_SECRETS_KEK_ACTIVE_KEY = $newKey
    PLATFORM_SECRETS_KEK_PREVIOUS_0_ID = 'rehearsal-1'; PLATFORM_SECRETS_KEK_PREVIOUS_0_KEY = $script:Env.SECRETS_KEK
}
$only = @{
    PLATFORM_SECRETS_KEK_ACTIVE_ID = 'rehearsal-2'; PLATFORM_SECRETS_KEK_ACTIVE_KEY = $newKey
    PLATFORM_SECRETS_KEK_PREVIOUS_0_ID = $null; PLATFORM_SECRETS_KEK_PREVIOUS_0_KEY = $null
}

$load = Start-Load 'rotate-kek' 8 '1000'
Set-ReleaseStarted $load

Say 'Release 1: the second key active, the first previous'
Invoke-RollingRelease -ApiImage 'platform-app:local' -SkipMigration -Override $both
$token = Get-Token
Check ((Write-Credential $token ('second-' + [guid]::NewGuid().ToString('N'))) -in '200', '201') 'a credential is written under the new key while the old one is still listed'
Check ((Get-KeyUse) -match 'rehearsal-2:\d+') 'the new version is wrapped by the second key'

$remaining = $true
for ($i = 1; $i -le 6 -and $remaining; $i++) {
    $null = Write-Credential $token ('again-' + $i + '-' + [guid]::NewGuid().ToString('N'))
    $remaining = (Get-KeyUse) -match 'rehearsal-1:\d+'
}
Check (-not $remaining) 'after a few writes no version needs the first key any more'

Say 'Release 2: the first key is removed'
Invoke-RollingRelease -ApiImage 'platform-app:local' -SkipMigration -Override $only
$ready = $true
foreach ($app in (Get-Replicas 'api')) { if ("$(Invoke-DockerQuiet exec $app wget -q -O- http://127.0.0.1:8081/actuator/health/readiness)" -notmatch '"status":"UP"') { $ready = $false } }
Check $ready 'the instances start and are ready without the first key'
$token = Get-Token
Check ((Write-Credential $token ('last-' + [guid]::NewGuid().ToString('N'))) -in '200', '201') 'a credential can still be written'
$list = "$(Invoke-Curl -H "Authorization: Bearer $token" "$org/api/v1/credentials")"
Check ($list -match 'rehearsal-cred' -and $list -notmatch 'last-') 'it is listed by its name and never by its value'

Start-Sleep -Seconds 10
$result = Stop-Load $load
Check $result.Passed 'during both releases no request failed'
Finish 'key-encryption key rotation'
