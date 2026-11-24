# One environment, from the same files as every other (Sprint 33, ADR-0292). The layers, in the order they depend on one
# another: network, data, secrets, edge, app, observability.

module "network" {
  source = "../modules/network"

  environment        = var.environment
  vpc_cidr           = var.vpc_cidr
  availability_zones = var.availability_zones
  nat_gateway_count  = var.nat_gateway_count
}

module "data" {
  source = "../modules/data"

  environment                 = var.environment
  data_subnet_ids             = module.network.data_subnet_ids
  database_security_group_id  = module.network.security_groups.database
  cache_security_group_id     = module.network.security_groups.cache
  db_instance_class           = var.db_instance_class
  db_allocated_storage_gb     = var.db_allocated_storage_gb
  db_max_allocated_storage_gb = var.db_max_allocated_storage_gb
  db_multi_az                 = var.db_multi_az
  db_backup_retention_days    = var.db_backup_retention_days
  db_deletion_protection      = var.db_deletion_protection
  db_keep_final_snapshot      = var.db_keep_final_snapshot
  cache_node_type             = var.cache_node_type
  cache_replicas              = var.cache_replicas
  cache_engine_version        = var.cache_engine_version
  files_bucket_name           = var.files_bucket_name
}

module "secrets" {
  source = "../modules/secrets"

  environment          = var.environment
  cache_password       = module.data.cache_password
  recovery_window_days = var.secret_recovery_days
}

module "edge" {
  source = "../modules/edge"

  environment                   = var.environment
  vpc_id                        = module.network.vpc_id
  public_subnet_ids             = module.network.public_subnet_ids
  balancer_security_group_id    = module.network.security_groups.balancer
  platform_domain               = var.platform_domain
  dns_zone_name                 = var.dns_zone_name
  operator_cidrs                = var.operator_cidrs
  mail_catcher                  = var.mail_catcher
  deletion_protection           = var.balancer_deletion_protection
  health_check_interval_seconds = var.health_check_interval_seconds
  unhealthy_threshold           = var.unhealthy_threshold
  deregistration_delay_seconds  = var.deregistration_delay_seconds
  idle_timeout_seconds          = var.idle_timeout_seconds
}

module "app" {
  source = "../modules/app"

  environment     = var.environment
  app_subnet_ids  = module.network.app_subnet_ids
  security_groups = module.network.security_groups
  vpc_id          = module.network.vpc_id
  vpc_cidr        = module.network.vpc_cidr
  platform_domain = var.platform_domain

  api_image               = "${var.image_registry}/platform-api:${var.api_image_tag}"
  web_image               = "${var.image_registry}/platform-web:${var.web_image_tag}"
  edge_image              = "${var.image_registry}/platform-edge:${var.edge_image_tag}"
  metrics_collector_image = var.metrics_collector_image

  target_groups = merge({
    api = module.edge.api_target_group_arn
    web = module.edge.web_target_group_arn
  }, var.mail_catcher ? { catcher = module.edge.catcher_target_group_arn } : {})

  database_address          = module.data.db_address
  database_owner_secret_arn = module.data.db_owner_secret_arn
  cache_address             = module.data.cache_address
  files_bucket_name         = module.data.files_bucket_name
  files_bucket_arn          = module.data.files_bucket_arn
  secret_arns               = module.secrets.arns

  api_cpu       = var.api_cpu
  api_memory    = var.api_memory
  api_min_tasks = var.api_min_tasks
  api_max_tasks = var.api_max_tasks
  web_cpu       = var.web_cpu
  web_memory    = var.web_memory
  web_min_tasks = var.web_min_tasks
  web_max_tasks = var.web_max_tasks

  log_retention_days = var.log_retention_days

  api_stop_timeout_seconds       = var.api_stop_timeout_seconds
  edge_stop_timeout_seconds      = var.edge_stop_timeout_seconds
  web_stop_timeout_seconds       = var.web_stop_timeout_seconds
  shutdown_delay_seconds         = var.shutdown_delay_seconds
  shutdown_phase_timeout_seconds = var.shutdown_phase_timeout_seconds
  web_shutdown_delay_seconds     = var.web_shutdown_delay_seconds

  smtp_host                = var.smtp_host
  smtp_port                = var.smtp_port
  smtp_credentials         = var.smtp_credentials
  mail_sender              = var.mail_sender
  mail_catcher             = var.mail_catcher
  webhooks_enabled         = var.webhooks_enabled
  integrations_enabled     = var.integrations_enabled
  sheet_format_two_enabled = var.sheet_format_two_enabled

  kek_active_id           = var.kek_active_id
  kek_previous_id         = var.kek_previous_id
  signing_active_slot     = var.signing_active_slot
  signing_active_key_id   = var.signing_active_key_id
  signing_retiring_slot   = var.signing_retiring_slot
  signing_retiring_key_id = var.signing_retiring_key_id
}

module "observability" {
  source = "../modules/observability"

  environment         = var.environment
  alerts_file         = "${path.root}/../../alerts/alerts.json"
  alarm_email         = var.alarm_email
  database_identifier = module.data.db_identifier
  monthly_budget_usd  = var.monthly_budget_usd

  dimension_values = {
    lb          = module.edge.balancer_arn_suffix
    api_tg      = module.edge.api_target_group_arn_suffix
    web_tg      = module.edge.web_target_group_arn_suffix
    db          = module.data.db_identifier
    cache       = module.data.cache_replication_group_id
    cluster     = module.app.cluster_name
    api_service = module.app.api_service_name
    certificate = module.edge.certificate_arn
  }

  thresholds = {
    api_min_tasks        = var.api_min_tasks
    db_connections_alarm = var.db_connections_alarm
  }

  log_groups = module.app.log_groups
}
