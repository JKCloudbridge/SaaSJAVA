# Where the state of STAGING is kept (the bucket is made once by the bootstrap folder). Use: tofu init -backend-config=values/staging.backend.hcl
# The state of an environment is its own object, in its own place: staging and production never share a state.
bucket       = "platform-tfstate-staging-CHANGE-ME"
key          = "staging/terraform.tfstate"
region       = "CHANGE-ME-region"
encrypt      = true
use_lockfile = true
