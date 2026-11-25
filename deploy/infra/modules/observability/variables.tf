variable "environment" {
  type = string
}

variable "alerts_file" {
  description = "deploy/alerts/alerts.json: the one list of alerts; an alarm is made from every entry."
  type        = string
}

variable "alarm_email" {
  description = "Where the alarms go first. Supplied when the plan is made (TF_VAR_alarm_email), never written in a file of this repository. The owner confirms the subscription from the mail the topic sends."
  type        = string
}

variable "dimension_values" {
  description = "The real values for the placeholders in the dimensions of alerts.json ({lb}, {api_tg}, {web_tg}, {db}, {cache}, {cluster}, {api_service}, {certificate})."
  type        = map(string)
}

variable "thresholds" {
  description = "The numbers an alert takes from the values file (threshold_variable in alerts.json)."
  type        = map(number)
}

variable "log_groups" {
  description = "The log groups that log-based alerts read, by short name."
  type        = map(string)
}

variable "database_identifier" {
  type = string
}

variable "monthly_budget_usd" {
  description = "The budget whose crossing is an alert."
  type        = number
}
