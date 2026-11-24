variable "environment" {
  type = string
}

variable "cache_password" {
  description = "The generated cache password (from the data module). Sensitive."
  type        = string
  sensitive   = true
}

variable "recovery_window_days" {
  description = "How long a removed secret can still be brought back (7 to 30; 0 removes at once and is for a throw-away environment only)."
  type        = number
}
