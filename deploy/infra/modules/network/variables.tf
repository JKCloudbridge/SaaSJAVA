variable "environment" {
  description = "The environment's name; part of every resource name."
  type        = string
}

variable "vpc_cidr" {
  description = "The address range of this environment's network. Environments never share a network, so ranges may repeat between them."
  type        = string
}

variable "availability_zones" {
  description = "Exactly two zones of the region: the database fails over between them and the services run in both."
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "Give exactly two availability zones."
  }
}

variable "nat_gateway_count" {
  description = "How many NAT gateways carry the outbound calls (webhooks, connectors, file transfer): 1 gives one fixed outbound address to document, 2 gives one per zone (two addresses, and no single zone whose loss stops every outbound call)."
  type        = number

  validation {
    condition     = contains([1, 2], var.nat_gateway_count)
    error_message = "nat_gateway_count is 1 or 2."
  }
}
