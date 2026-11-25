# Staging (Sprint 33, ADR-0291): the same files as production, smaller sizes, and two things production never has: a mail
# catcher (no mail can reach a real person) and no webhook or connector traffic (no real customer address is ever called).
# Everything marked CHANGE-ME is the owner's decision or the owner's account, and is replaced before the first plan
# (docs/runbooks/environments.md, "Sprint 33 manual steps"). Nothing here is a secret: no password, key or token is ever
# written in a values file, and scripts/check-secrets.sh fails the build if one appears.

environment = "staging"
region      = "CHANGE-ME-region"

platform_domain = "staging.platform.example.test" # CHANGE-ME: the staging name under the owner's domain
dns_zone_name   = "platform.example.test"         # CHANGE-ME: the zone the owner created for the domain

vpc_cidr           = "10.40.0.0/16"
availability_zones = ["CHANGE-ME-region-a", "CHANGE-ME-region-b"]
nat_gateway_count  = 1
operator_cidrs     = [] # CHANGE-ME: the owner's own address range, so the mail catcher's screen can be opened

image_registry          = "registry.example.test" # CHANGE-ME: the registry host the release procedure pushes to (made by infra/bootstrap)
metrics_collector_image = "CHANGE-ME-collector-image-with-a-pinned-version"

# ---- data ----
db_instance_class           = "db.t4g.medium" # staging is not a benchmark target; production's class is chosen after the storage benchmark
db_allocated_storage_gb     = 50
db_max_allocated_storage_gb = 200
db_multi_az                 = false
db_backup_retention_days    = 7
db_deletion_protection      = false
db_keep_final_snapshot      = false
db_connections_alarm        = 150

cache_node_type      = "cache.t4g.micro"
cache_replicas       = 0
cache_engine_version = "7.1"

files_bucket_name = "platform-staging-files-CHANGE-ME" # globally unique; never shared with another environment

# ---- sizes ----
api_cpu       = 1024
api_memory    = 2048
api_min_tasks = 2
api_max_tasks = 3
web_cpu       = 256
web_memory    = 512
web_min_tasks = 1
web_max_tasks = 2

# ---- the drain rule (ADR-0289): scripts/check-drain-numbers.sh keeps these in order ----
shutdown_delay_seconds         = 10
shutdown_phase_timeout_seconds = 30
web_shutdown_delay_seconds     = 10
api_stop_timeout_seconds       = 90
edge_stop_timeout_seconds      = 30
web_stop_timeout_seconds       = 30
health_check_interval_seconds  = 5
unhealthy_threshold            = 2
deregistration_delay_seconds   = 60
idle_timeout_seconds           = 130

# ---- the application ----
smtp_host        = "unused-with-a-catcher"
smtp_port        = 587
smtp_credentials = false
mail_sender      = "no-reply@staging.platform.example.test" # CHANGE-ME: under staging_domain
mail_catcher     = true

webhooks_enabled     = false
integrations_enabled = false

# Identifiers only (the keys themselves are in the secret store). Replaced when a key is rotated.
kek_active_id         = "staging-1"
signing_active_slot   = "a"
signing_active_key_id = "staging-1"

# ---- operating it ----
log_retention_days           = 14
monthly_budget_usd           = 150 # CHANGE-ME: the owner's own figure
secret_recovery_days         = 7
balancer_deletion_protection = false
