<#
 Regenerates the committed OpenAPI document from the running application and stops. Run it after changing a
 controller or an API contract type, review the diff, and commit it together with the regenerated frontend client
 (cd platform-web; npm run api:generate). Needs a container runtime (the test starts a real database).
 See docs/adr/0011-api-conventions.md.
#>
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
./mvnw -B -ntp -pl platform-app -am verify -Pintegration-only `
    '-Dit.test=OpenApiContractIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dopenapi.update=true'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host 'Updated platform-api-contract/src/main/resources/openapi/platform-api-v1.json'
