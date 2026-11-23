<#
 Makes the throw-away credentials and keys the rehearsal needs: .env (passwords, the storage keys, the key of the
 secrets store) and secrets/ (a P-256 signing key pair as PEM files, made by a one-off container so no tool but Docker
 is needed). Both are ignored by git. Repeatable: what exists is kept; delete .env and secrets/ to start again
 (and `docker compose down -v` to forget the data).
 Nothing made here is a real secret: it is only ever used on this machine.
#>
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function New-Token([int]$length) {
    $chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'
    -join (1..$length | ForEach-Object { $chars[(Get-Random -Maximum $chars.Length)] })
}

if (-not (Test-Path .env)) {
    $kek = [Convert]::ToBase64String((1..32 | ForEach-Object { [byte](Get-Random -Maximum 256) }))
    @(
        'BASE_DOMAIN=platform.test',
        'POSTGRES_USER=platform_owner',
        'POSTGRES_DB=platform',
        "POSTGRES_PASSWORD=$(New-Token 24)",
        "APP_DB_PASSWORD=$(New-Token 24)",
        "REDIS_PASSWORD=$(New-Token 24)",
        "S3_ACCESS_KEY=$(New-Token 16)",
        "S3_SECRET_KEY=$(New-Token 32)",
        "SECRETS_KEK=$kek"
    ) | Set-Content -Path .env -Encoding ascii
    Write-Host 'Made .env'
} else { Write-Host '.env exists: kept' }

New-Item -ItemType Directory -Force secrets | Out-Null
if (-not (Test-Path secrets/signing-private.pem)) {
    docker run --rm -v "${PWD}/secrets:/out" --entrypoint sh alpine/openssl -c `
        "openssl ecparam -name prime256v1 -genkey -noout -out /out/ec.pem && openssl pkcs8 -topk8 -nocrypt -in /out/ec.pem -out /out/signing-private.pem && openssl ec -in /out/ec.pem -pubout -out /out/signing-public.pem && rm /out/ec.pem && chmod 644 /out/*.pem"
    if ($LASTEXITCODE -ne 0) { throw 'Could not make the signing keys.' }
    Write-Host 'Made secrets/signing-private.pem and signing-public.pem'
} else { Write-Host 'Signing keys exist: kept' }
