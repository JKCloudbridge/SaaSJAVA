# Alarms, the topic they go to, the database events and the budget of one environment (Sprint 33, ADR-0294). The alarms are
# made from deploy/alerts/alerts.json, so the list a person reads in runbooks/alerts.md and the alarms that exist are the same
# list (scripts/check-alerts.sh holds them together).

locals {
  name   = "platform-${var.environment}"
  config = jsondecode(file(var.alerts_file))

  alerts = { for alert in local.config.alerts : alert.id => alert }

  # Log-based alerts are made from a metric filter on the application's log, so they need a filter too.
  from_logs = { for id, alert in local.alerts : id => alert if alert.source == "log" }
}

resource "aws_sns_topic" "alerts" {
  name = "${local.name}-alerts"
}

data "aws_caller_identity" "current" {}

# Who may publish to the topic: this account's own alarms, and the two services that report on their own (the budget and the
# database events).
data "aws_iam_policy_document" "alerts" {
  statement {
    sid       = "ThisAccount"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "AWS"
      identifiers = [data.aws_caller_identity.current.account_id]
    }
  }

  statement {
    sid       = "BudgetsAndDatabaseEvents"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["budgets.amazonaws.com", "events.rds.amazonaws.com", "cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts.json
}

# The owner's address is subscribed first; the subscription must be confirmed from the mail the topic sends (manual step).
resource "aws_sns_topic_subscription" "owner" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_cloudwatch_log_metric_filter" "alert" {
  for_each = local.from_logs

  name           = "${local.name}-${each.key}"
  log_group_name = var.log_groups[each.value.log_group]
  pattern        = each.value.pattern

  metric_transformation {
    name          = each.value.metric
    namespace     = "Platform/${var.environment}"
    value         = "1"
    default_value = "0"
  }
}

resource "aws_cloudwatch_metric_alarm" "alert" {
  for_each = local.alerts

  alarm_name        = "${local.name}-${each.key}"
  alarm_description = "${each.value.summary}. ${each.value.meaning} Who: ${each.value.who}. Runbook: docs/runbooks/alerts.md#${each.key}"

  comparison_operator = each.value.comparison
  evaluation_periods  = each.value.evaluation_periods
  period              = each.value.period
  threshold           = try(each.value.threshold, var.thresholds[each.value.threshold_variable])
  treat_missing_data  = each.value.missing

  # A statistic such as p95 is an extended statistic; the others are plain.
  statistic          = startswith(each.value.statistic, "p") ? null : each.value.statistic
  extended_statistic = startswith(each.value.statistic, "p") ? each.value.statistic : null

  # The metric of an AWS service, of the application (written by the metrics collector of the task) or of a log filter.
  namespace = (
    each.value.source == "aws" ? each.value.namespace : "Platform/${var.environment}"
  )
  metric_name = each.value.metric

  # An application metric carries the environment as a dimension; an AWS one has the dimensions the entry lists; a metric made
  # from a log filter has none.
  dimensions = (
    each.value.source == "log" ? {} :
    each.value.source == "app" ? merge({ environment = var.environment }, each.value.dimensions) :
    { for key, value in each.value.dimensions : key => try(var.dimension_values[trim(value, "{}")], value) }
  )

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  depends_on = [aws_cloudwatch_log_metric_filter.alert]
}

# The database service's own events: a failed backup, a failover, low storage.
resource "aws_db_event_subscription" "database" {
  name      = "${local.name}-database"
  sns_topic = aws_sns_topic.alerts.arn

  source_type      = "db-instance"
  source_ids       = [var.database_identifier]
  event_categories = ["backup", "failure", "failover", "low storage", "recovery"]
}

# Spending: told at 80 percent of the budget and when the forecast says the budget will be passed.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 80
    threshold_type            = "PERCENTAGE"
    notification_type         = "ACTUAL"
    subscriber_sns_topic_arns = [aws_sns_topic.alerts.arn]
  }

  notification {
    comparison_operator       = "GREATER_THAN"
    threshold                 = 100
    threshold_type            = "PERCENTAGE"
    notification_type         = "FORECASTED"
    subscriber_sns_topic_arns = [aws_sns_topic.alerts.arn]
  }
}
