output "arns" {
  description = "The ARN of each secret, by its short name."
  value       = { for name, secret in aws_secretsmanager_secret.entry : name => secret.arn }
}

output "prefix" {
  value = local.prefix
}
