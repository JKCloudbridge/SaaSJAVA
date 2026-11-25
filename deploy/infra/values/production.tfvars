# Production (Sprint 33, ADR-0291): the same files as staging, real sizes, a standby database, deletion protection and a final
# snapshot, and the real mail provider. Everything marked CHANGE-ME is the owner's decision or the owner's account, and is
# replaced before the first plan. Nothing here is a secret: no password, key or token is ever written in a values file, and
# scripts/check-secrets.sh fails the build if one appears.

environment = "production"
region      = "CHANGE-ME-region"

platform_domain = "platform.example.test" # CHANGE-ME: the owner's domain
dns_zone_name   = "platform.example.test" # CHANGE-ME

vpc_cidr           = "10.50.0.0/16"
availability_zones = ["CHANGE-ME-region-a", "CHANGE-ME-region-b"]
nat_gateway_count  = 1 # one fixed outbound address to tell customers; 2 gives one per zone (runbooks/outbound-addresses.md)
operator_cidrs     = []

image_registry          = "registry.example.test" # CHANGE-ME: the same registry as staging: an image is built once and promoted
metrics_collector_image = "CHANGE-ME-collector-image-with-a-pinned-version"

# ---- data ----
db_instance_class           = "db.CHANGE-ME-after-the-storage-benchmark" # ADR-0083 condition 6: run the benchmark on the candidates first
db_allocated_storage_gb     = 100
db_max_allocated_storage_gb = 1000
db_multi_az                 = true
db_backup_retention_days    = 14
db_deletion_protection      = true
db_keep_final_snapshot      = true
db_connections_alarm        = 300 # CHANGE-ME with the instance class: see runbooks/database.md (the arithmetic)

cache_node_type      = "cache.t4g.small"
cache_replicas       = 1
cache_engine_version = "7.1"

files_bucket_name = "platform-production-files-CHANGE-ME"

# ---- sizes ----
api_cpu       = 2048
api_memory    = 4096
api_min_tasks = 2
api_max_tasks = 8
web_cpu       = 512
web_memory    = 1024
web_min_tasks = 2
web_max_tasks = 4

# ---- the drain rule (ADR-0289): the same numbers as staging, so what staging proves is what production does ----
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
smtp_host        = "CHANGE-ME-smtp-host-of-the-mail-provider"
smtp_port        = 587
smtp_credentials = true
mail_sender      = "no-reply@platform.example.test" # CHANGE-ME: under a domain verified with the mail provider
mail_catcher     = false

webhooks_enabled     = true
integrations_enabled = true

# Identifiers only (the keys themselves are in the secret store). Replaced when a key is rotated.
kek_active_id         = "production-1"
signing_active_slot   = "a"
signing_active_key_id = "production-1"

# ---- operating it ----
log_retention_days           = 90
monthly_budget_usd           = 600 # CHANGE-ME: the owner's own figure
secret_recovery_days         = 30
balancer_deletion_protection = true
