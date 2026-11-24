variable "environment" {
  type = string
}

variable "data_subnet_ids" {
  type = list(string)
}

variable "database_security_group_id" {
  type = string
}

variable "cache_security_group_id" {
  type = string
}

variable "db_engine_version" {
  description = "PostgreSQL major version. 18 is mandatory: the migrations use the built-in uuidv7() that only version 18 has."
  type        = string
  default     = "18"
}

variable "db_instance_class" {
  description = "Chosen after the storage benchmark has been run on the candidates (ADR-0083 condition 6, the manual steps)."
  type        = string
}

variable "db_allocated_storage_gb" {
  type = number
}

variable "db_max_allocated_storage_gb" {
  description = "The storage grows by itself up to this size."
  type        = number
}

variable "db_multi_az" {
  description = "A standby in the other zone with automatic failover. On in production; staging may run without."
  type        = bool
}

variable "db_backup_retention_days" {
  description = "Automated backups and point-in-time recovery for this many days (at least 1: 0 would switch them off)."
  type        = number

  validation {
    condition     = var.db_backup_retention_days >= 1 && var.db_backup_retention_days <= 35
    error_message = "db_backup_retention_days is between 1 and 35."
  }
}

variable "db_deletion_protection" {
  description = "On in production: the database cannot be deleted until this is switched off in a separate, deliberate change."
  type        = bool
}

variable "db_keep_final_snapshot" {
  description = "Take a final snapshot when the database is destroyed. Always true in production."
  type        = bool
}

variable "cache_node_type" {
  type = string
}

variable "cache_replicas" {
  description = "Replicas besides the primary (0 for staging, 1 for production gives automatic failover of the cache)."
  type        = number
}

variable "cache_engine_version" {
  description = "The platform targets Redis 8 and uses only basic commands (counters and expiry); the managed cache may offer an older line. Verify with the rehearsal's checks before choosing (the manual steps)."
  type        = string
}

variable "files_bucket_name" {
  description = "The object store bucket of files, imports and custom page scripts. Globally unique; never shared by two environments."
  type        = string
}

variable "files_noncurrent_days" {
  description = "How long an earlier version of a stored file is kept after it was replaced or removed."
  type        = number
  default     = 30
}
