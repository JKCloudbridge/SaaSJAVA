output "db_address" {
  value = aws_db_instance.main.address
}

output "db_identifier" {
  value = aws_db_instance.main.identifier
}

output "db_owner_secret_arn" {
  description = "The provider-managed secret that holds the owner role's password (read only by the migration job)."
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "cache_address" {
  value = aws_elasticache_replication_group.main.primary_endpoint_address
}

output "cache_replication_group_id" {
  value = aws_elasticache_replication_group.main.replication_group_id
}

output "cache_password" {
  description = "Handed to the secrets module, which stores it. Marked sensitive so it never prints."
  value       = random_password.cache.result
  sensitive   = true
}

output "files_bucket_name" {
  value = aws_s3_bucket.files.bucket
}

output "files_bucket_arn" {
  value = aws_s3_bucket.files.arn
}
