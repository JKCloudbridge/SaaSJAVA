[CmdletBinding()]
param()

# Windows holds a running jar open. Stop only this checkout's jar before packaging it again.
$ErrorActionPreference = 'Stop'
$repository = Split-Path -Parent $PSScriptRoot
$jar = [IO.Path]::GetFullPath((Join-Path $repository 'platform-app/target/platform-app-0.1.0-SNAPSHOT.jar'))
$target = Split-Path -Parent $jar

foreach ($candidate in Get-CimInstance Win32_Process -Filter "Name = 'java.exe'") {
    if ($candidate.CommandLine -match '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))') {
        $runningJar = if ($Matches[1]) { $Matches[1] } else { $Matches[2] }
        # A relative path cannot identify a checkout reliably; never stop an unrelated Java process.
        if ([IO.Path]::IsPathRooted($runningJar) -and
                [IO.Path]::GetFullPath($runningJar) -eq $jar) {
            Write-Host "Stopping this checkout's backend (PID $($candidate.ProcessId))..."
            Stop-Process -Id $candidate.ProcessId
            Wait-Process -Id $candidate.ProcessId -Timeout 20 -ErrorAction SilentlyContinue
        }
    }
}

Push-Location $repository
try {
    Write-Host 'Packaging the application...'
    & .\mvnw.cmd -B -ntp -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw 'Packaging failed. The backend was not started; see the Maven error above.'
    }

    $standardOutput = Join-Path $target 'local-backend-out.log'
    $standardError = Join-Path $target 'local-backend-err.log'
    $backend = Start-Process -FilePath 'java' -ArgumentList '-jar', ('"' + $jar + '"'),
        '--spring.profiles.active=local' -WorkingDirectory $repository -WindowStyle Hidden `
        -RedirectStandardOutput $standardOutput -RedirectStandardError $standardError -PassThru
    Write-Host "Starting the updated backend (PID $($backend.Id))..."
    $deadline = (Get-Date).AddSeconds(60)
    do {
        $backend.Refresh()
        if ($backend.HasExited) {
            throw "The backend exited. Read $standardOutput and $standardError."
        }
        try {
            $response = Invoke-WebRequest -Uri 'http://localhost:8081/actuator/health/readiness' `
                -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                Write-Host 'Backend ready at http://localhost:8080. Packaging and restart completed.'
                Write-Host "Logs: $standardOutput"
                return
            }
        } catch {
            # Readiness is unavailable while Spring starts; keep the startup wait bounded.
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw "Readiness was not reached within 60 seconds. Read $standardOutput and $standardError."
} finally {
    Pop-Location
}
