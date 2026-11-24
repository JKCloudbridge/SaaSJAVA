<#
 Rotating the token signing key without a failed sign-in (ADR-0290, runbooks/signing-key-rotation.md).

 What the keys sign: ID tokens, which are published through the key set (access tokens are opaque and are not signed by them).
 The configuration lists keys: the FIRST signs, every key in the list is published. Replacing a key in ONE release (new key first,
 old key gone) would make the instances not yet released sign or publish a different set than the ones already released, and
 anything that read the key set a moment before would not know the key an ID token now carries. So it is three releases, each a
 rolling release under load:
   1  the new key is added SECOND: the old one still signs, the new one is only published (it is known everywhere before it signs)
   2  the new key moves FIRST: it signs, the old one is still published (an ID token signed before still verifies)
   3  the old key is removed, after the longest ID token life has passed
 The load signs in again every 20 seconds throughout (a sign-in issues an ID token); any failed step counts as a failed request.
#>
param([int]$WaitBeforeRemoval = 50)
. "$PSScriptRoot/lib.ps1"
Invoke-Seed
Set-Names
$org = "https://$($script:Organization).$($script:Domain)"
$platform = "https://$($script:Domain)"
$keysB = Join-Path $script:Run 'key-b'
if (-not (Test-Path (Join-Path $keysB 'signing-private.pem'))) {
    New-Item -ItemType Directory -Force $keysB | Out-Null
    Invoke-Docker run --rm -v "${keysB}:/out" --entrypoint sh alpine/openssl -c `
        'openssl ecparam -name prime256v1 -genkey -noout -out /out/ec.pem && openssl pkcs8 -topk8 -nocrypt -in /out/ec.pem -out /out/signing-private.pem && openssl ec -in /out/ec.pem -pubout -out /out/signing-public.pem && rm /out/ec.pem && chmod 644 /out/*.pem'
}
function Get-Base64([string]$path) { [Convert]::ToBase64String([IO.File]::ReadAllBytes($path)) }
$keyA = @{ Id = 'rehearsal-1'; Private = Get-Base64 (Join-Path $PSScriptRoot 'secrets/signing-private.pem'); Public = Get-Base64 (Join-Path $PSScriptRoot 'secrets/signing-public.pem') }
$keyB = @{ Id = 'rehearsal-2'; Private = Get-Base64 (Join-Path $keysB 'signing-private.pem'); Public = Get-Base64 (Join-Path $keysB 'signing-public.pem') }

function New-Two($first, $second) {
    # Both keys are written to files by the image's entry point; the list is given in its indexed form for both entries, because a
    # list given from the environment replaces the one in the configuration file as a whole.
    $slots = @{ $first.Id = 'a'; $second.Id = 'b' }
    return @{
        PLATFORM_SIGNING_KEY_ID = $null; PLATFORM_SIGNING_PRIVATE_KEY_FILE = $null; PLATFORM_SIGNING_PUBLIC_KEY_FILE = $null
        PLATFORM_KEYFILE_B64__signing_a_private = $first.Private; PLATFORM_KEYFILE_B64__signing_a_public = $first.Public
        PLATFORM_KEYFILE_B64__signing_b_private = $second.Private; PLATFORM_KEYFILE_B64__signing_b_public = $second.Public
        PLATFORM_IDENTITY_SIGNING_KEYS_0_ID = $first.Id
        PLATFORM_IDENTITY_SIGNING_KEYS_0_PRIVATE_KEY_FILE = '/tmp/platform-keys/signing_a_private'
        PLATFORM_IDENTITY_SIGNING_KEYS_0_PUBLIC_KEY_FILE = '/tmp/platform-keys/signing_a_public'
        PLATFORM_IDENTITY_SIGNING_KEYS_1_ID = $second.Id
        PLATFORM_IDENTITY_SIGNING_KEYS_1_PRIVATE_KEY_FILE = '/tmp/platform-keys/signing_b_private'
        PLATFORM_IDENTITY_SIGNING_KEYS_1_PUBLIC_KEY_FILE = '/tmp/platform-keys/signing_b_public'
    }
}
function New-Only($key) {
    return @{
        PLATFORM_SIGNING_KEY_ID = $key.Id
        PLATFORM_SIGNING_PRIVATE_KEY_FILE = '/tmp/platform-keys/signing_a_private'
        PLATFORM_SIGNING_PUBLIC_KEY_FILE = '/tmp/platform-keys/signing_a_public'
        PLATFORM_KEYFILE_B64__signing_a_private = $key.Private; PLATFORM_KEYFILE_B64__signing_a_public = $key.Public
        PLATFORM_KEYFILE_B64__signing_b_private = $null; PLATFORM_KEYFILE_B64__signing_b_public = $null
    }
}
function Get-Token {
    $arguments = Get-JavaArguments @('--mode=token')
    $answer = (& java @arguments 2>&1) -join "`n"
    if ($answer -match 'TOKEN (\S+)') { return $Matches[1] }
    throw "Could not sign in: $answer"
}
function Get-Kid([string]$token) {
    $header = $token.Split('.')[0].Replace('-', '+').Replace('_', '/')
    while ($header.Length % 4 -ne 0) { $header += '=' }
    return ([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($header)) | ConvertFrom-Json).kid
}
function Get-Status([string]$token) { "$(Invoke-Curl -o NUL -w '%{http_code}' -H "Authorization: Bearer $token" "$org/api/v1/auth/me")" }
function Get-PublishedKeys { "$(Invoke-Curl "$platform/api/v1/oauth2/jwks")" }

$old = Get-Token
Check (((Get-PublishedKeys) -match 'rehearsal-1') -and ((Get-PublishedKeys) -notmatch 'rehearsal-2')) 'before the rotation only the first key is published'
Check ((Get-Status $old) -eq '200') 'an access token made before the rotation works (access tokens are opaque; the keys sign ID tokens only)'

$load = Start-Load 'rotate-signing' 10 '1000' 20
Set-ReleaseStarted $load

Say 'Release 1: the new key is added second (it verifies, the old one still signs)'
Invoke-RollingRelease -ApiImage 'platform-app:local' -SkipMigration -Override (New-Two $keyA $keyB)
$published = Get-PublishedKeys
Check ($published -match 'rehearsal-1' -and $published -match 'rehearsal-2' -and $published -notmatch '"d"') 'both public keys are published, no private part'
Check ((Get-Status $old) -eq '200') 'the token made before the rotation still works'
Check ((Get-Status (Get-Token)) -eq '200') 'a fresh sign-in works while the new key only verifies'

Say 'Release 2: the new key moves first (it signs, the old one still verifies)'
Invoke-RollingRelease -ApiImage 'platform-app:local' -SkipMigration -Override (New-Two $keyB $keyA)
Check ((Get-Status (Get-Token)) -eq '200') 'a fresh sign-in works while the new key signs'
Check ((Get-Status $old) -eq '200') 'the token made before the rotation still works'

Say "Waiting $WaitBeforeRemoval seconds: the longest life of a token that the old key signed must pass"
Start-Sleep -Seconds $WaitBeforeRemoval

Say 'Release 3: the old key is removed'
Invoke-RollingRelease -ApiImage 'platform-app:local' -SkipMigration -Override (New-Only $keyB)
$published = Get-PublishedKeys
Check ($published -match 'rehearsal-2' -and $published -notmatch 'rehearsal-1') 'only the new public key is published'
Check ((Get-Status $old) -eq '200') 'removing the old key does not touch an access token already issued (it is opaque)'
Check ((Get-Status (Get-Token)) -eq '200') 'a fresh sign-in works'

Start-Sleep -Seconds 15
$result = Stop-Load $load
Check $result.Passed 'during all three releases no request failed and no token that should have worked was refused'
Finish 'signing key rotation without an unwanted logout'
