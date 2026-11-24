<#
 Starts a clean rehearsal environment the way a deployment starts one (Sprint 33, ADR-0288): the data services, the
 one-time steps (the application role of manual migration M001, the bucket), THE MIGRATION JOB FIRST as the owner, and only then
 the application replicas (each with its edge sidecar) and the web replicas, behind the ingress. Finally it seeds one
 organization with an object, for the checks and the load generator.

 Usage:  ./up.ps1                      (two application replicas, two web replicas)
         ./up.ps1 -Replicas 3
 Needs the images: platform-app:local and platform-web:local (see README.md); the edge image is built here if it is missing.
 Prints PASS or FAIL for what it proved.
#>
param([int]$Replicas = 2, [int]$WebReplicas = 2)
. "$PSScriptRoot/lib.ps1"

& (Join-Path $PSScriptRoot 'init.ps1')
$script:Env = Read-DotEnv

foreach ($image in 'platform-app:local', 'platform-web:local') {
    $found = Invoke-DockerQuiet image inspect $image
    if ($LASTEXITCODE -ne 0) { throw "The image $image does not exist: build it first (README.md, step 2)." }
}
$edge = Invoke-DockerQuiet image inspect platform-edge:local
if ($LASTEXITCODE -ne 0) {
    Say 'Building the edge sidecar image'
    Invoke-Docker build -t platform-edge:local (Join-Path $PSScriptRoot '../../deploy/edge')
}

Say 'Data services'
Invoke-Compose up -d --wait postgres redis object-storage mail-catcher
Say 'One-time steps: the application role (M001) and the bucket'
Invoke-Compose --profile jobs run --rm role-init
Invoke-Compose --profile jobs run --rm bucket-init
Say 'The migration job, before any replica'
$migration = Get-Date
Invoke-MigrationJob 'platform-app:local'
$version = Get-OwnerSql 'select platform_schema_version()'
Check ($version -match '^\d+$' -and [int]$version -ge 97) "the migration job ran first and the schema is at version $version"

Say 'Ingress'
Invoke-Compose up -d ingress

Say "Starting $Replicas application replicas and $WebReplicas web replicas"
$apps = @()
for ($i = 1; $i -le $Replicas; $i++) { $apps += Start-Api (Get-NextIndex 'api') 'platform-app:local' }
$webs = @()
for ($i = 1; $i -le $WebReplicas; $i++) { $webs += Start-Web (Get-NextIndex 'web') 'platform-web:local' }
foreach ($app in $apps) { Wait-ApiReady $app | Out-Null }
foreach ($web in $webs) { Wait-WebReady $web }
Wait-IngressSees 8

Set-Names
$status = Invoke-Curl -o NUL -w '%{http_code}' "https://$($script:Domain)/api/v1/platform/status"
Check ("$status" -eq '200') "the platform status answers 200 through the ingress over https (got $status)"
$page = Invoke-Curl -o NUL -w '%{http_code}' "https://$($script:Domain)/healthz"
Check ("$page" -eq '200') "the web server answers through the ingress (got $page)"

# A replica that tries to start on a schema that is behind its release must refuse, with one clear sentence (ADR-0288).
Say 'Seeding the rehearsal organization'
Invoke-Seed
Check (Test-Path (Join-Path $script:Run 'state.txt')) 'the rehearsal organization and its object exist'
Say "Release stamp of the first replica: $(Get-Stamp $apps[0])"
Finish 'clean start with the migration job first'
