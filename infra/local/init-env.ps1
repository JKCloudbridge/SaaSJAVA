<#
 Creates infra/local/.env with random local-only credentials. Safe to re-run: it never overwrites an existing .env
 unless -Force is given (overwriting changes passwords, so existing data volumes keep the old ones until you run
 `docker compose down -v`). When the file exists but lacks variables that .env.example has (a new sprint added some),
 only the missing ones are appended, with random values for credentials.
#>
param([switch]$Force)

$target = Join-Path $PSScriptRoot '.env'
$examplePath = Join-Path $PSScriptRoot '.env.example'

function New-RandomSecret([int]$bytes = 24) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    # URL-safe, no characters that need quoting in compose or connection strings.
    return ([Convert]::ToBase64String($buffer) -replace '[+/=]', 'x')
}

# Which variables hold credentials, and how long a random value each gets.
$secrets = @{ POSTGRES_PASSWORD = 24; REDIS_PASSWORD = 24; S3_ACCESS_KEY = 12; S3_SECRET_KEY = 30; APP_DB_PASSWORD = 24; LOCAL_SEED_PASSWORD = 24 }

if ((Test-Path $target) -and -not $Force) {
    $existing = @{}
    Get-Content $target | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object { $existing[($_ -split '=', 2)[0]] = $true }
    $added = @()
    foreach ($line in (Get-Content $examplePath | Where-Object { $_ -match '^[A-Z0-9_]+=' })) {
        $key = ($line -split '=', 2)[0]
        if (-not $existing.ContainsKey($key)) {
            if ($secrets.ContainsKey($key)) { $line = "$key=$(New-RandomSecret $secrets[$key])" }
            $added += $line
        }
    }
    if ($added.Count -eq 0) {
        Write-Host ".env already exists and is complete, leaving it unchanged (use -Force to regenerate)."
        return
    }
    $text = [System.IO.File]::ReadAllText($target)
    if (-not $text.EndsWith("`n")) { $text += "`n" }
    $text += "`n# Added by init-env.ps1`n" + ($added -join "`n") + "`n"
    [System.IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Added $($added.Count) missing variable(s) to $target : $((($added | ForEach-Object { ($_ -split '=', 2)[0] }) -join ', '))."
    if ($added -match '^APP_DB_') {
        Write-Host "Next: ./init-app-role.ps1 creates the application database role with that password."
    }
    return
}

$content = Get-Content $examplePath -Raw
foreach ($key in $secrets.Keys) {
    $content = $content -replace "(?m)^$key=.*$", "$key=$(New-RandomSecret $secrets[$key])"
}
[System.IO.File]::WriteAllText($target, $content, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Created $target with random local-only credentials."
Write-Host "After 'docker compose up -d', run ./init-app-role.ps1 once to create the application database role."
