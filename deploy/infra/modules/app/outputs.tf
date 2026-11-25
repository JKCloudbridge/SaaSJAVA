output "cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "api_service_name" {
  value = aws_ecs_service.api.name
}

output "web_service_name" {
  value = aws_ecs_service.web.name
}

output "migrate_task_definition" {
  description = "The family of the migration job's definition: the release procedure runs it before the services are updated."
  value       = aws_ecs_task_definition.migrate.family
}

output "log_groups" {
  value = {
    api     = aws_cloudwatch_log_group.service["api"].name
    web     = aws_cloudwatch_log_group.service["web"].name
    migrate = aws_cloudwatch_log_group.service["migrate"].name
    metrics = aws_cloudwatch_log_group.metrics.name
  }
}
