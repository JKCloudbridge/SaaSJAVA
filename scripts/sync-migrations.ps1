<#
 Mirrors the repository's database migrations into the human-readable "Migrations" folder.

 There is ONE source of truth: the files in the repository.
   Automatic (run by the application on start-up, versioned by the application's migration tool):
       platform-app/src/main/resources/db/migration/V<nnn>__<snake_case_name>.sql
   Manual (must be run by a person with elevated rights, never by the application):
       db/manual/M<nnn>__<snake_case_name>.sql

 The Migrations folder is a generated, read-only mirror with the names the project uses for humans:
       V001__create_tenant_table.sql  ->  001 create tenant table.sql
       M001__create_app_roles.sql     ->  Manual\001 create app roles.sql
 Never edit the mirror: change the source file and run this script again.

 Usage:
   ./scripts/sync-migrations.ps1            copy the sources into the mirror
   ./scripts/sync-migrations.ps1 -Check     report differences and exit 1 if the mirror is stale (changes nothing)
#>
param(
    [switch]$Check,
    [string]$Source = (Join-Path $PSScriptRoot '..\platform-app\src\main\resources\db\migration'),
    [string]$ManualSource = (Join-Path $PSScriptRoot '..\db\manual'),
    [string]$Target = (Join-Path $PSScriptRoot '..\..\Shree Ganeshay Namah\Migrations')
)

$ErrorActionPreference = 'Stop'

function Get-Expected([string]$sourceDir, [string]$pattern, [string]$targetDir) {
    $map = @{}
    if (-not (Test-Path $sourceDir)) { return $map }
    foreach ($file in Get-ChildItem -Path $sourceDir -Filter '*.sql' -File) {
        if ($file.Name -cnotmatch $pattern) {
            throw "Migration file name '$($file.Name)' does not match the convention $pattern"
        }
        $number = $Matches[1]
        $name = $Matches[2] -replace '_', ' '
        $map[(Join-Path $targetDir "$number $name.sql")] = $file.FullName
    }
    return $map
}

$autoDir = $Target
$manualDir = Join-Path $Target 'Manual'
$expected = @{}
(Get-Expected $Source '^V(\d{3})__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$' $autoDir).GetEnumerator() | ForEach-Object { $expected[$_.Key] = $_.Value }
(Get-Expected $ManualSource '^M(\d{3})__([a-z0-9]+(?:_[a-z0-9]+)*)\.sql$' $manualDir).GetEnumerator() | ForEach-Object { $expected[$_.Key] = $_.Value }

# Duplicate numbers within one source folder would be ambiguous.
foreach ($dir in @($autoDir, $manualDir)) {
    $numbers = $expected.Keys | Where-Object { (Split-Path $_ -Parent) -eq $dir } | ForEach-Object { (Split-Path $_ -Leaf).Substring(0, 3) }
    $dupes = $numbers | Group-Object | Where-Object { $_.Count -gt 1 }
    if ($dupes) { throw "Duplicate migration number(s): $($dupes.Name -join ', ')" }
}

$problems = @()
foreach ($entry in $expected.GetEnumerator()) {
    if (-not (Test-Path $entry.Key)) {
        $problems += "missing in mirror: $($entry.Key | Split-Path -Leaf)"
    } elseif ((Get-FileHash $entry.Key).Hash -ne (Get-FileHash $entry.Value).Hash) {
        $problems += "differs from source: $($entry.Key | Split-Path -Leaf)"
    }
}
$existing = @()
foreach ($dir in @($autoDir, $manualDir)) {
    if (Test-Path $dir) { $existing += Get-ChildItem -Path $dir -Filter '*.sql' -File }
}
foreach ($file in $existing) {
    if (-not $expected.ContainsKey($file.FullName)) { $problems += "stale (no source): $($file.FullName)" }
}

if ($Check) {
    if ($problems.Count -gt 0) {
        $problems | ForEach-Object { Write-Host "  $_" }
        Write-Host "Mirror is out of date. Run ./scripts/sync-migrations.ps1"
        exit 1
    }
    Write-Host "Mirror is up to date ($($expected.Count) file(s))."
    return
}

foreach ($dir in @($autoDir, $manualDir)) {
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
}
foreach ($file in $existing) {
    if (-not $expected.ContainsKey($file.FullName)) {
        $file.IsReadOnly = $false
        Remove-Item $file.FullName
        Write-Host "removed stale mirror file: $($file.Name)"
    }
}
foreach ($entry in $expected.GetEnumerator()) {
    if (Test-Path $entry.Key) { (Get-Item $entry.Key).IsReadOnly = $false }
    Copy-Item -Path $entry.Value -Destination $entry.Key -Force
    (Get-Item $entry.Key).IsReadOnly = $true
}
Write-Host "Mirrored $($expected.Count) migration file(s) into $Target"
