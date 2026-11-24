output "alert_topic_arn" {
  value = aws_sns_topic.alerts.arn
}

output "alarm_names" {
  value = [for alarm in aws_cloudwatch_metric_alarm.alert : alarm.alarm_name]
}
