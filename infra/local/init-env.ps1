<#
 Creates infra/local/.env with random local-only credentials. Safe to re-run: it never overwrites an
 existing .env unless -Force is given (overwriting changes passwords, so existing data volumes keep the
 old ones until you run `docker compose down -v`).
#>
param([switch]$Force)

$target = Join-Path $PSScriptRoot '.env'
if ((Test-Path $target) -and -not $Force) {
    Write-Host ".env already exists, leaving it unchanged (use -Force to regenerate)."
    return
}

function New-RandomSecret([int]$bytes = 24) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # URL-safe, no characters that need quoting in compose or connection strings.
    return ([Convert]::ToBase64String($buffer) -replace '[+/=]', 'x')
}

$content = Get-Content (Join-Path $PSScriptRoot '.env.example') -Raw
$content = $content -replace '(?m)^POSTGRES_PASSWORD=.*$', "POSTGRES_PASSWORD=$(New-RandomSecret)"
$content = $content -replace '(?m)^REDIS_PASSWORD=.*$', "REDIS_PASSWORD=$(New-RandomSecret)"
$content = $content -replace '(?m)^S3_ACCESS_KEY=.*$', "S3_ACCESS_KEY=$(New-RandomSecret 12)"
$content = $content -replace '(?m)^S3_SECRET_KEY=.*$', "S3_SECRET_KEY=$(New-RandomSecret 30)"
[System.IO.File]::WriteAllText($target, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Created $target with random local-only credentials."
