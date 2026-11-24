# Every input of an environment. One values file per environment (values/staging.tfvars, values/production.tfvars) gives them;
# the files of this folder are the same for every environment, so staging is a true copy of production in everything but size.

variable "environment" {
  type = string

  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment is staging or production."
  }
}

variable "region" {
  type = string
}

variable "platform_domain" {
  description = "Organizations live at <slug>.<this>; one wildcard certificate covers it."
  type        = string
}

variable "dns_zone_name" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "availability_zones" {
  type = list(string)
}

variable "nat_gateway_count" {
  type = number
}

variable "operator_cidrs" {
  description = "Addresses that may open a test environment's mail catcher screen (never needed in production)."
  type        = list(string)
  default     = []
}

# ---- images: the registry is shared by every environment (an artifact store, read only here); the tags are release identifiers ----
variable "image_registry" {
  description = "The registry host the images are in (the repositories platform-api, platform-web and platform-edge are made by infra/bootstrap)."
  type        = string
}

variable "api_image_tag" {
  description = "Given by the release procedure (-var api_image_tag=...), never written in a values file."
  type        = string
}

variable "web_image_tag" {
  type = string
}

variable "edge_image_tag" {
  type = string
}

variable "metrics_collector_image" {
  type = string
}

# ---- the data layer ----
variable "db_instance_class" {
  type = string
}

variable "db_allocated_storage_gb" {
  type = number
}

variable "db_max_allocated_storage_gb" {
  type = number
}

variable "db_multi_az" {
  type = bool
}

variable "db_backup_retention_days" {
  type = number
}

variable "db_deletion_protection" {
  type = bool
}

variable "db_keep_final_snapshot" {
  type = bool
}

variable "db_connections_alarm" {
  description = "Open connections that raise the alert. The arithmetic is in runbooks/database.md: instances times the pool, plus the jobs, must stay under it, which stays under the server's limit."
  type        = number
}

variable "cache_node_type" {
  type = string
}

variable "cache_replicas" {
  type = number
}

variable "cache_engine_version" {
  type = string
}

variable "files_bucket_name" {
  type = string
}

# ---- sizes ----
variable "api_cpu" {
  type = number
}

variable "api_memory" {
  type = number
}

variable "api_min_tasks" {
  type = number
}

variable "api_max_tasks" {
  type = number
}

variable "web_cpu" {
  type = number
}

variable "web_memory" {
  type = number
}

variable "web_min_tasks" {
  type = number
}

variable "web_max_tasks" {
  type = number
}

# ---- the numbers of the drain rule (ADR-0289); scripts/check-drain-numbers.sh keeps them in order ----
variable "shutdown_delay_seconds" {
  type = number
}

variable "shutdown_phase_timeout_seconds" {
  type = number
}

variable "web_shutdown_delay_seconds" {
  type = number
}

variable "api_stop_timeout_seconds" {
  type = number
}

variable "edge_stop_timeout_seconds" {
  type = number
}

variable "web_stop_timeout_seconds" {
  type = number
}

variable "health_check_interval_seconds" {
  type = number
}

variable "unhealthy_threshold" {
  type = number
}

variable "deregistration_delay_seconds" {
  type = number
}

variable "idle_timeout_seconds" {
  type = number
}

# ---- what differs by environment in the application's settings ----
variable "smtp_host" {
  type = string
}

variable "smtp_port" {
  type = number
}

variable "smtp_credentials" {
  type = bool
}

variable "mail_sender" {
  type = string
}

variable "mail_catcher" {
  type = bool
}

variable "webhooks_enabled" {
  type = bool
}

variable "integrations_enabled" {
  type = bool
}

variable "sheet_format_two_enabled" {
  type    = bool
  default = false
}

# ---- keys: identifiers only; the values are in the secret store ----
variable "kek_active_id" {
  type = string
}

variable "kek_previous_id" {
  type    = string
  default = ""
}

variable "signing_active_slot" {
  type = string
}

variable "signing_active_key_id" {
  type = string
}

variable "signing_retiring_slot" {
  type    = string
  default = ""
}

variable "signing_retiring_key_id" {
  type    = string
  default = ""
}

# ---- operating it ----
variable "alarm_email" {
  description = "Supplied at plan time (TF_VAR_alarm_email); never written in a file."
  type        = string
}

variable "log_retention_days" {
  type = number
}

variable "monthly_budget_usd" {
  type = number
}

variable "secret_recovery_days" {
  type = number
}

variable "balancer_deletion_protection" {
  type = bool
}
