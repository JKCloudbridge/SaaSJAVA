#!/usr/bin/env bash
# Regenerates the committed OpenAPI document from the running application and stops. Run it after changing a
# controller or an API contract type, review the diff, and commit it. Needs a container runtime (the test starts a
# real database). See docs/adr/0011-api-conventions.md.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -B -ntp -pl platform-app -am verify -Pintegration-only \
  -Dit.test=OpenApiContractIT -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.update=true
echo "Updated platform-api-contract/src/main/resources/openapi/platform-api-v1.json"
