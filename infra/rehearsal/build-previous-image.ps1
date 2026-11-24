<#
 Builds the PREVIOUS release's application image, for the rollback test (Sprint 33, ADR-0288): the commit before this work. The
 commit is exported with `git archive` into a folder of the rehearsal's own (nothing of git is changed: no branch, no worktree, no
 stash) and built there, so nothing of the working folder is touched. It runs Maven (a few minutes) and Docker.
 Result: the image platform-app:previous.
#>
param([string]$Ref = 'HEAD')
. "$PSScriptRoot/lib.ps1"
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tree = Join-Path $script:Run 'previous-tree'
if (Test-Path $tree) { Remove-Item -Recurse -Force $tree }
New-Item -ItemType Directory -Force $tree | Out-Null
$sha = (& git -C $root rev-parse --short=12 $Ref).Trim()
$tar = Join-Path $script:Run 'previous.tar'
& git -C $root archive --format=tar -o $tar $Ref
if ($LASTEXITCODE -ne 0) { throw 'git archive failed.' }
& tar -xf $tar -C $tree
Remove-Item $tar
try {
    Push-Location $tree
    try {
        & .\mvnw.cmd -B -ntp -DskipTests -pl platform-app -am package
        if ($LASTEXITCODE -ne 0) { throw 'The build of the previous release failed.' }
    } finally { Pop-Location }
    Invoke-Docker build -t platform-app:previous --build-arg "GIT_COMMIT=$sha" (Join-Path $tree 'platform-app')
    Say "Built platform-app:previous from $sha"
} finally {
    Remove-Item -Recurse -Force $tree -ErrorAction SilentlyContinue
}
