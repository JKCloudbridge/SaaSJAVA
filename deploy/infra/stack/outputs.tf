output "nat_addresses" {
  description = "The fixed addresses every outbound call of this environment leaves from. Tell a customer who allows calls by address (runbooks/outbound-addresses.md)."
  value       = module.network.nat_addresses
}

output "database_address" {
  value = module.data.db_address
}

output "database_owner_secret_arn" {
  description = "Where the provider keeps the owner role's password (the owner reads it for manual migrations M001 and M002)."
  value       = module.data.db_owner_secret_arn
}

output "cluster_name" {
  value = module.app.cluster_name
}

output "api_service_name" {
  value = module.app.api_service_name
}

output "web_service_name" {
  value = module.app.web_service_name
}

output "migrate_task_definition" {
  value = module.app.migrate_task_definition
}

output "app_subnet_ids" {
  description = "Where the migration job runs."
  value       = module.network.app_subnet_ids
}

output "migrate_security_group_id" {
  value = module.network.security_groups.migrate
}

output "secret_prefix" {
  value = module.secrets.prefix
}

output "alert_topic_arn" {
  value = module.observability.alert_topic_arn
}
