variable "environment" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "balancer_security_group_id" {
  type = string
}

variable "platform_domain" {
  description = "The platform's domain. Organizations live at <slug>.<platform_domain>; the certificate covers it and every name under it."
  type        = string
}

variable "dns_zone_name" {
  description = "The DNS zone that holds platform_domain (it must already exist: the owner creates it with the domain)."
  type        = string
}

variable "operator_cidrs" {
  description = "Addresses that may open the mail catcher's screen of a test environment. Empty: nobody can."
  type        = list(string)
  default     = []
}

variable "mail_catcher" {
  type = bool
}

variable "deletion_protection" {
  type = bool
}

# The numbers of the drain rule (ADR-0289). scripts/check-drain-numbers.sh reads them from the values file and fails when
# the order between them is wrong, so they are variables with no default: they are written once, in the values file.
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
  description = "Longer than the longest time limit of any path class of the edge rules (120 seconds)."
  type        = number
}
