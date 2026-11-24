output "balancer_arn_suffix" {
  value = aws_lb.main.arn_suffix
}

output "balancer_dns_name" {
  value = aws_lb.main.dns_name
}

output "api_target_group_arn" {
  value = aws_lb_target_group.api.arn
}

output "api_target_group_arn_suffix" {
  value = aws_lb_target_group.api.arn_suffix
}

output "web_target_group_arn" {
  value = aws_lb_target_group.web.arn
}

output "web_target_group_arn_suffix" {
  value = aws_lb_target_group.web.arn_suffix
}

output "catcher_target_group_arn" {
  value = try(aws_lb_target_group.catcher[0].arn, null)
}

output "certificate_arn" {
  value = aws_acm_certificate.platform.arn
}
