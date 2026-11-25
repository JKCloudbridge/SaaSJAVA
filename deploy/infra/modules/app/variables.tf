variable "environment" {
  type = string
}

variable "app_subnet_ids" {
  type = list(string)
}

variable "security_groups" {
  description = "From the network module: api, web, migrate and catcher."
  type        = map(string)
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  description = "The network's range: the edge sidecar trusts the load balancer inside it as the one hop that appends to X-Forwarded-For."
  type        = string
}

variable "platform_domain" {
  type = string
}

# ---- images: immutable tags, built once and promoted (ADR-0287) ----
variable "api_image" {
  description = "Full reference of the application image, tag included. The tag is a release identifier; 'latest' is refused."
  type        = string

  validation {
    condition     = !endswith(var.api_image, ":latest") && can(regex(":", var.api_image))
    error_message = "The image reference needs an immutable tag, never latest."
  }
}

variable "web_image" {
  type = string

  validation {
    condition     = !endswith(var.web_image, ":latest") && can(regex(":", var.web_image))
    error_message = "The image reference needs an immutable tag, never latest."
  }
}

variable "edge_image" {
  type = string

  validation {
    condition     = !endswith(var.edge_image, ":latest") && can(regex(":", var.edge_image))
    error_message = "The image reference needs an immutable tag, never latest."
  }
}

variable "metrics_collector_image" {
  description = "The collector that reads the application's metrics on the management port and writes them to the metric store. Pin a version the owner has checked."
  type        = string
}

variable "mail_catcher_image" {
  type    = string
  default = "axllent/mailpit:v1.31"
}

# ---- what the tasks are connected to ----
variable "target_groups" {
  description = "api, web and (when there is a catcher) catcher target group ARNs, from the edge module."
  type        = map(string)
}

variable "database_address" {
  type = string
}

variable "database_owner_secret_arn" {
  description = "Read only by the migration job's own execution role."
  type        = string
}

variable "cache_address" {
  type = string
}

variable "files_bucket_name" {
  type = string
}

variable "files_bucket_arn" {
  type = string
}

variable "secret_arns" {
  description = "From the secrets module, by short name."
  type        = map(string)
}

# ---- sizes and counts ----
variable "api_cpu" {
  type = number
}

variable "api_memory" {
  type = number
}

variable "api_min_tasks" {
  description = "Never fewer than this many (at least 2 where a release must not drop requests: one task is replaced while the other serves)."
  type        = number
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

variable "log_retention_days" {
  type = number
}

# ---- the numbers of the drain rule (ADR-0289); checked by scripts/check-drain-numbers.sh ----
variable "api_stop_timeout_seconds" {
  description = "Time the container platform waits between the stop signal and the kill, for the application container (the edge sidecar has its own and stops after it)."
  type        = number
}

variable "edge_stop_timeout_seconds" {
  type = number
}

variable "web_stop_timeout_seconds" {
  type = number
}

variable "shutdown_delay_seconds" {
  type = number
}

variable "shutdown_phase_timeout_seconds" {
  type = number
}

variable "web_shutdown_delay_seconds" {
  type = number
}

# ---- application settings that differ by environment ----
variable "smtp_host" {
  description = "Ignored when a mail catcher is used (the catcher's own name is used)."
  type        = string
}

variable "smtp_port" {
  type = number
}

variable "smtp_credentials" {
  description = "Whether the mail server needs a user and a password from the secret store (false for a catcher)."
  type        = bool
}

variable "mail_sender" {
  description = "The address mails come from; it belongs to a domain the owner has verified with the mail provider."
  type        = string
}

variable "mail_catcher" {
  description = "Run a mail catcher and send every mail to it: the environment can never mail a real person (ADR-0291)."
  type        = bool
}

variable "webhooks_enabled" {
  description = "false in staging: no webhook is queued or sent, so no real customer address is ever called."
  type        = bool
}

variable "integrations_enabled" {
  description = "false in staging: no delivery or file transfer to an external system is made."
  type        = bool
}

variable "sheet_format_two_enabled" {
  description = "Whether a sheet may be stored in format version 2 (keep false until the whole sheet engine is released)."
  type        = bool
}

variable "kek_active_id" {
  description = "The identifier (not the value) of the key that wraps the secrets store's data keys."
  type        = string
}

variable "kek_previous_id" {
  description = "The identifier of the previous key while one is being replaced; empty otherwise (then the kek-previous secret is not used)."
  type        = string
  default     = ""
}

variable "signing_active_slot" {
  description = "Which slot (a or b) of the secret store holds the key that signs tokens."
  type        = string

  validation {
    condition     = contains(["a", "b"], var.signing_active_slot)
    error_message = "signing_active_slot is a or b."
  }
}

variable "signing_active_key_id" {
  type = string
}

variable "signing_retiring_slot" {
  description = "The slot of the key that still verifies while the new one takes over; empty when no key is retiring."
  type        = string
  default     = ""

  validation {
    condition     = contains(["", "a", "b"], var.signing_retiring_slot)
    error_message = "signing_retiring_slot is empty, a or b."
  }
}

variable "signing_retiring_key_id" {
  type    = string
  default = ""
}

variable "cache_tls" {
  type    = bool
  default = true
}
