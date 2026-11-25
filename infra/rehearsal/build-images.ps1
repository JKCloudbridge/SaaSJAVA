<#
 Builds the three images of the rehearsal (Sprint 33, ADR-0287) with the release stamp, from the jar that ./mvnw package made:
   platform-app:<Tag>   the application (and, with its `migrate` command, the migration job)
   platform-web:<Tag>   the web server (runs `next build` inside Docker; it does not touch the dev server's folders)
   platform-edge:<Tag>  the edge sidecar
 -Tag local is what ./up.ps1 starts. -Tag next is "a new release" for ./release.ps1: the same code with another stamp, so the
 release mechanics are tried without waiting for a second build of the whole application.
 Stop the local backend first if you rebuild the jar (a running jar locks the file).
#>
param([string]$Tag = 'local', [string]$Commit = '', [switch]$SkipWeb)
. "$PSScriptRoot/lib.ps1"
$root = Resolve-Path (Join-Path $PSScriptRoot '../..')
if ($Commit -eq '') {
    $Commit = (& git -C $root rev-parse --short=12 HEAD).Trim()
    if ($Tag -eq 'next') { $Commit = ($Commit.Substring(0, 6) + 'abcdef') }
}
if ($Commit -notmatch '^[0-9a-f]{7,40}$') { throw 'The commit stamp must be hexadecimal.' }
$time = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
$jar = Get-ChildItem (Join-Path $root 'platform-app/target') -Filter 'platform-app-*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { throw 'There is no jar: run ./mvnw -B -ntp -DskipTests package first.' }

Invoke-Docker build -t "platform-app:$Tag" --build-arg "GIT_COMMIT=$Commit" --build-arg "BUILD_TIME=$time" (Join-Path $root 'platform-app')
Invoke-Docker build -t "platform-edge:$Tag" --build-arg "GIT_COMMIT=$Commit" --build-arg "BUILD_TIME=$time" (Join-Path $root 'deploy/edge')
if ($Tag -ne 'local') { Invoke-Docker tag "platform-edge:$Tag" platform-edge:local }
if (-not $SkipWeb) {
    if ($Tag -eq 'next') {
        # The web server's code did not change between "local" and "next"; a second full build would only cost time.
        Invoke-Docker tag platform-web:local platform-web:next
    } else {
        Invoke-Docker build -t "platform-web:$Tag" --build-arg "GIT_COMMIT=$Commit" --build-arg "BUILD_TIME=$time" (Join-Path $root 'platform-web')
    }
}
Say "Built platform-app:$Tag, platform-edge:$Tag and platform-web:$Tag (stamp $Commit, $time)"
