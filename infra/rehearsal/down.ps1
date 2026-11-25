<#
 Stops the rehearsal. The replicas are removed; the data is kept unless -Forget is given (then the volumes, the throw-away
 state of the seeded organization and the certificate authority are forgotten too, and the next ./up.ps1 starts from nothing).
#>
param([switch]$Forget)
. "$PSScriptRoot/lib.ps1"

foreach ($name in (Invoke-DockerQuiet ps -a --filter 'name=rehearsal-' --format '{{.Names}}')) {
    if ($name -match '^rehearsal-(api|edge|web)-\d+$') { Invoke-DockerQuiet rm -f $name | Out-Null }
}
if ($Forget) {
    Invoke-Compose --profile jobs down -v
    if (Test-Path $script:Run) { Remove-Item -Recurse -Force $script:Run }
    Say 'Everything forgotten.'
} else {
    Invoke-Compose --profile jobs down
    Say 'Stopped. The data is kept (./up.ps1 starts again; -Forget forgets it).'
}
