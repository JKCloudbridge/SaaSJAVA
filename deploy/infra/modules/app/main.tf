# The services of one environment (Sprint 33, ADR-0286, ADR-0289, ADR-0290): the application (with its edge sidecar and its
# metrics collector), the web server, the migration job's definition, and, in a test environment, a mail catcher.
#
# The order of a stop is the point of this file. When a task is stopped the container platform sends the stop signal to its
# containers in the REVERSE of their start order, one after the other. The application depends on the edge sidecar, so the
# application stops first (it says "not ready", keeps serving for the shutdown delay, finishes what is in flight and exits),
# and the sidecar, which keeps proxying until then, stops last. The stop timeout of the application is longer than its own
# worst case, and the numbers are held in order by scripts/check-drain-numbers.sh.

data "aws_region" "current" {}

locals {
  name        = "platform-${var.environment}"
  region      = data.aws_region.current.region
  keyfile_dir = "/tmp/platform-keys"

  smtp_host = var.mail_catcher ? "mail.${local.name}.internal" : var.smtp_host

  # ---- the signing keys: one, or two while a key is being replaced (runbooks/signing-key-rotation.md) ----
  active_slot   = var.signing_active_slot
  retiring_slot = var.signing_retiring_slot

  signing_env = local.retiring_slot == "" ? {
    PLATFORM_SIGNING_KEY_ID           = var.signing_active_key_id
    PLATFORM_SIGNING_PRIVATE_KEY_FILE = "${local.keyfile_dir}/signing_${local.active_slot}_private"
    PLATFORM_SIGNING_PUBLIC_KEY_FILE  = "${local.keyfile_dir}/signing_${local.active_slot}_public"
    } : {
    # Two keys are given in the indexed form for both: a list given from the environment replaces the one in the
    # configuration file as a whole, so the first key has to be named here too.
    PLATFORM_IDENTITY_SIGNING_KEYS_0_ID               = var.signing_active_key_id
    PLATFORM_IDENTITY_SIGNING_KEYS_0_PRIVATE_KEY_FILE = "${local.keyfile_dir}/signing_${local.active_slot}_private"
    PLATFORM_IDENTITY_SIGNING_KEYS_0_PUBLIC_KEY_FILE  = "${local.keyfile_dir}/signing_${local.active_slot}_public"
    PLATFORM_IDENTITY_SIGNING_KEYS_1_ID               = var.signing_retiring_key_id
    PLATFORM_IDENTITY_SIGNING_KEYS_1_PRIVATE_KEY_FILE = "${local.keyfile_dir}/signing_${local.retiring_slot}_private"
    PLATFORM_IDENTITY_SIGNING_KEYS_1_PUBLIC_KEY_FILE  = "${local.keyfile_dir}/signing_${local.retiring_slot}_public"
  }

  signing_slots_in_use = compact([local.active_slot, local.retiring_slot])

  kek_env = var.kek_previous_id == "" ? {
    PLATFORM_SECRETS_KEK_ACTIVE_ID = var.kek_active_id
    } : {
    PLATFORM_SECRETS_KEK_ACTIVE_ID     = var.kek_active_id
    PLATFORM_SECRETS_KEK_PREVIOUS_0_ID = var.kek_previous_id
  }

  # ---- what the application is told (no value of a secret is here; secrets are named below) ----
  api_environment = merge({
    SPRING_PROFILES_ACTIVE = "prod"
    PLATFORM_ENVIRONMENT   = var.environment

    # The application runs on 8090 behind its sidecar, which takes the traffic on 8080 (ADR-0286).
    PLATFORM_HTTP_PORT       = "8090"
    PLATFORM_MANAGEMENT_PORT = "8081"

    PLATFORM_DB_URL           = "jdbc:postgresql://${var.database_address}:5432/platform?sslmode=require"
    PLATFORM_DB_APP_USER      = "platform_app"
    PLATFORM_MIGRATE_ON_START = "false" # the migration job runs first; this task only checks the schema (ADR-0288)

    PLATFORM_BASE_DOMAIN     = var.platform_domain
    PLATFORM_PUBLIC_BASE_URL = "https://${var.platform_domain}"

    # The load balancer preserves the Host header, which is what the tenant is taken from; X-Forwarded-Host is never
    # trusted. The sidecar writes X-Forwarded-For as one address, the caller's (ADR-0286).
    PLATFORM_TRUST_FORWARDED_HOST = "false"
    PLATFORM_TRUST_FORWARDED_FOR  = "true"
    PLATFORM_FORWARDED_FOR_HOPS   = "1"

    PLATFORM_REDIS_HOST           = var.cache_address
    PLATFORM_REDIS_PORT           = "6379"
    SPRING_DATA_REDIS_SSL_ENABLED = tostring(var.cache_tls)

    PLATFORM_SMTP_HOST              = local.smtp_host
    PLATFORM_SMTP_PORT              = tostring(var.mail_catcher ? 1025 : var.smtp_port)
    PLATFORM_SMTP_AUTH              = tostring(var.smtp_credentials)
    PLATFORM_SMTP_STARTTLS_REQUIRED = tostring(!var.mail_catcher)
    PLATFORM_MAIL_SENDER            = var.mail_sender

    # The task has a role for the files bucket: no access key and no secret exist (ADR-0290).
    PLATFORM_STORAGE_BUCKET      = var.files_bucket_name
    PLATFORM_STORAGE_REGION      = local.region
    PLATFORM_STORAGE_CREDENTIALS = "role"

    PLATFORM_WEBHOOKS_ENABLED                = tostring(var.webhooks_enabled)
    PLATFORM_INTEGRATION_RELIABILITY_ENABLED = tostring(var.integrations_enabled)
    PLATFORM_INTEGRATION_FILES_ENABLED       = tostring(var.integrations_enabled)
    PLATFORM_SHEET_FORMAT_TWO_ENABLED        = tostring(var.sheet_format_two_enabled)

    PLATFORM_SHUTDOWN_DELAY   = "${var.shutdown_delay_seconds}s"
    PLATFORM_SHUTDOWN_TIMEOUT = "${var.shutdown_phase_timeout_seconds}s"

    PLATFORM_KEYFILE_DIR = local.keyfile_dir
  }, local.signing_env, local.kek_env)

  api_secrets = merge({
    PLATFORM_DB_APP_PASSWORD        = var.secret_arns["db-app-password"]
    PLATFORM_REDIS_PASSWORD         = var.secret_arns["cache-password"]
    PLATFORM_SECRETS_KEK_ACTIVE_KEY = var.secret_arns["kek-active"]
    },
    var.smtp_credentials ? {
      PLATFORM_SMTP_USER     = var.secret_arns["smtp-user"]
      PLATFORM_SMTP_PASSWORD = var.secret_arns["smtp-password"]
    } : {},
    var.kek_previous_id == "" ? {} : { PLATFORM_SECRETS_KEK_PREVIOUS_0_KEY = var.secret_arns["kek-previous"] },
    # Key files: the entry point of the image writes each of these to a private file and removes it from the environment.
    { for slot in local.signing_slots_in_use : "PLATFORM_KEYFILE__signing_${slot}_private" => var.secret_arns["signing-${slot}-private"] },
    { for slot in local.signing_slots_in_use : "PLATFORM_KEYFILE__signing_${slot}_public" => var.secret_arns["signing-${slot}-public"] },
  )

  log_group = {
    api     = aws_cloudwatch_log_group.service["api"].name
    web     = aws_cloudwatch_log_group.service["web"].name
    migrate = aws_cloudwatch_log_group.service["migrate"].name
    catcher = aws_cloudwatch_log_group.service["catcher"].name
    metrics = aws_cloudwatch_log_group.metrics.name
  }

  # The collector reads the application's metrics from its management port, inside the task, and writes them to the metric
  # store as embedded metric records. The metric names are those of the exposition format; the first run on a real
  # environment confirms them (the manual steps), and scripts/check-alerts.sh holds alerts.json to this list.
  collector_config = yamlencode({
    receivers = {
      prometheus = {
        config = {
          scrape_configs = [{
            job_name        = "platform"
            scrape_interval = "30s"
            metrics_path    = "/actuator/prometheus"
            static_configs  = [{ targets = ["127.0.0.1:8081"] }]
          }]
        }
      }
    }
    processors = { batch = {} }
    exporters = {
      awsemf = {
        namespace      = "Platform/${var.environment}"
        log_group_name = local.log_group.metrics
        region         = local.region
        metric_declarations = [{
          dimensions = [["environment"], ["environment", "result"]]
          metric_name_selectors = [
            "platform_outbox_events.*", "platform_notification_mail.*", "platform_workflow_.*",
            "platform_integration_.*", "platform_webhook_.*", "platform_identity_ratelimit_degraded.*",
            "platform_approval_.*", "platform_limits_.*", "http_server_requests_seconds.*",
          ]
        }]
      }
    }
    service = {
      pipelines = {
        metrics = { receivers = ["prometheus"], processors = ["batch"], exporters = ["awsemf"] }
      }
    }
  })
}

# ---- logs ----

resource "aws_cloudwatch_log_group" "service" {
  for_each = toset(["api", "web", "migrate", "catcher"])

  name              = "/platform/${var.environment}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "metrics" {
  name              = "/platform/${var.environment}/metrics"
  retention_in_days = var.log_retention_days
}

# ---- roles ----

data "aws_iam_policy_document" "tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# The execution roles pull images, write logs and read exactly the secrets their own task names.
resource "aws_iam_role" "execution" {
  for_each = toset(["api", "web", "migrate", "catcher"])

  name               = "${local.name}-${each.key}-execution"
  assume_role_policy = data.aws_iam_policy_document.tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  for_each = aws_iam_role.execution

  role       = each.value.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "api_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = distinct(values(local.api_secrets))
  }
}

resource "aws_iam_role_policy" "api_secrets" {
  name   = "read-own-secrets"
  role   = aws_iam_role.execution["api"].id
  policy = data.aws_iam_policy_document.api_secrets.json
}

# The migration job reads the database owner's password and nothing else; the application never can.
data "aws_iam_policy_document" "migrate_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.database_owner_secret_arn]
  }
}

resource "aws_iam_role_policy" "migrate_secrets" {
  name   = "read-the-owner-password"
  role   = aws_iam_role.execution["migrate"].id
  policy = data.aws_iam_policy_document.migrate_secrets.json
}

# The application's own role: the files bucket, and the metric records of its collector.
resource "aws_iam_role" "api_task" {
  name               = "${local.name}-api-task"
  assume_role_policy = data.aws_iam_policy_document.tasks_assume.json
}

data "aws_iam_policy_document" "api_task" {
  statement {
    sid       = "FilesBucketObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:AbortMultipartUpload"]
    resources = ["${var.files_bucket_arn}/*"]
  }

  statement {
    sid       = "FilesBucketList"
    actions   = ["s3:ListBucket"]
    resources = [var.files_bucket_arn]
  }

  statement {
    sid       = "MetricRecords"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.metrics.arn}:*"]
  }
}

resource "aws_iam_role_policy" "api_task" {
  name   = "files-and-metrics"
  role   = aws_iam_role.api_task.id
  policy = data.aws_iam_policy_document.api_task.json
}

# ---- the cluster ----

resource "aws_ecs_cluster" "main" {
  name = local.name

  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

# ---- the application task: edge sidecar, application, metrics collector ----

resource "aws_ecs_task_definition" "api" {
  family                   = "${local.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution["api"].arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([
    {
      # Takes the traffic of the load balancer on 8080 and keeps the rules of deploy/edge/api-rules.caddy. It starts first and
      # stops last: the application depends on it.
      name         = "edge"
      image        = var.edge_image
      essential    = true
      stopTimeout  = var.edge_stop_timeout_seconds
      portMappings = [{ containerPort = 8080, protocol = "tcp" }]
      environment  = [{ name = "TRUSTED_CIDR", value = var.vpc_cidr }]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://127.0.0.1:8080/edge-ready || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 60
      }
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.api, "awslogs-region" = local.region, "awslogs-stream-prefix" = "edge" }
      }
    },
    {
      name        = "api"
      image       = var.api_image
      essential   = true
      command     = ["serve"]
      stopTimeout = var.api_stop_timeout_seconds
      dependsOn   = [{ containerName = "edge", condition = "START" }]
      portMappings = [
        { containerPort = 8090, protocol = "tcp" },
        { containerPort = 8081, protocol = "tcp" },
      ]
      environment = [for key, value in local.api_environment : { name = key, value = value }]
      secrets     = [for key, arn in local.api_secrets : { name = key, valueFrom = arn }]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 90
      }
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.api, "awslogs-region" = local.region, "awslogs-stream-prefix" = "api" }
      }
    },
    {
      name      = "metrics"
      image     = var.metrics_collector_image
      essential = false
      environment = [
        { name = "AOT_CONFIG_CONTENT", value = local.collector_config },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.api, "awslogs-region" = local.region, "awslogs-stream-prefix" = "metrics" }
      }
    },
  ])
}

resource "aws_ecs_service" "api" {
  name            = "${local.name}-api"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.api.arn
  desired_count   = var.api_min_tasks
  launch_type     = "FARGATE"

  # A release starts the new tasks beside the old ones and removes an old one only when a new one is healthy: capacity never
  # falls below what is running (ADR-0289). A release that does not become healthy is rolled back by the circuit breaker.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 180

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_groups["api"]]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_groups["api"]
    container_name   = "edge"
    container_port   = 8080
  }

  lifecycle {
    ignore_changes = [desired_count] # the scaling policy owns the count
  }
}

resource "aws_appautoscaling_target" "api" {
  max_capacity       = var.api_max_tasks
  min_capacity       = var.api_min_tasks
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${local.name}-api-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  service_namespace  = aws_appautoscaling_target.api.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# ---- the web task ----

resource "aws_ecs_task_definition" "web" {
  family                   = "${local.name}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.web_cpu
  memory                   = var.web_memory
  execution_role_arn       = aws_iam_role.execution["web"].arn

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([
    {
      name         = "web"
      image        = var.web_image
      essential    = true
      stopTimeout  = var.web_stop_timeout_seconds
      portMappings = [{ containerPort = 3000, protocol = "tcp" }]
      environment = [
        { name = "WEB_SHUTDOWN_DELAY", value = tostring(var.web_shutdown_delay_seconds) },
      ]
      healthCheck = {
        command     = ["CMD-SHELL", "wget -q --spider http://127.0.0.1:3000/healthz || exit 1"]
        interval    = 15
        timeout     = 5
        retries     = 3
        startPeriod = 30
      }
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.web, "awslogs-region" = local.region, "awslogs-stream-prefix" = "web" }
      }
    },
  ])
}

resource "aws_ecs_service" "web" {
  name            = "${local.name}-web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = var.web_min_tasks
  launch_type     = "FARGATE"

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  health_check_grace_period_seconds  = 60

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_groups["web"]]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_groups["web"]
    container_name   = "web"
    container_port   = 3000
  }

  lifecycle {
    ignore_changes = [desired_count]
  }
}

resource "aws_appautoscaling_target" "web" {
  max_capacity       = var.web_max_tasks
  min_capacity       = var.web_min_tasks
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.web.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "web_cpu" {
  name               = "${local.name}-web-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.web.resource_id
  scalable_dimension = aws_appautoscaling_target.web.scalable_dimension
  service_namespace  = aws_appautoscaling_target.web.service_namespace

  target_tracking_scaling_policy_configuration {
    target_value       = 60
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# ---- the migration job: a definition only; the release procedure runs it and waits for it (runbooks/deploy.md) ----

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${local.name}-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.execution["migrate"].arn

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([
    {
      name      = "migrate"
      image     = var.api_image # the same image as the application: the same migration files and the same library
      essential = true
      command   = ["migrate"]
      environment = [
        { name = "PLATFORM_DB_URL", value = "jdbc:postgresql://${var.database_address}:5432/platform?sslmode=require" },
        { name = "PLATFORM_DB_OWNER_USER", value = "platform_owner" },
      ]
      # The owner's password is a field of the secret the database service keeps; the application's task cannot read it.
      secrets = [
        { name = "PLATFORM_DB_OWNER_PASSWORD", valueFrom = "${var.database_owner_secret_arn}:password::" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.migrate, "awslogs-region" = local.region, "awslogs-stream-prefix" = "migrate" }
      }
    },
  ])
}

# ---- a mail catcher, only in a test environment ----

resource "aws_service_discovery_private_dns_namespace" "internal" {
  count = var.mail_catcher ? 1 : 0

  name = "${local.name}.internal"
  vpc  = var.vpc_id
}

resource "aws_service_discovery_service" "mail" {
  count = var.mail_catcher ? 1 : 0

  name = "mail"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.internal[0].id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }
}

resource "aws_ecs_task_definition" "catcher" {
  count = var.mail_catcher ? 1 : 0

  family                   = "${local.name}-mail-catcher"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution["catcher"].arn

  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  container_definitions = jsonencode([
    {
      name      = "catcher"
      image     = var.mail_catcher_image
      essential = true
      portMappings = [
        { containerPort = 1025, protocol = "tcp" },
        { containerPort = 8025, protocol = "tcp" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options   = { "awslogs-group" = local.log_group.catcher, "awslogs-region" = local.region, "awslogs-stream-prefix" = "catcher" }
      }
    },
  ])
}

resource "aws_ecs_service" "catcher" {
  count = var.mail_catcher ? 1 : 0

  name            = "${local.name}-mail-catcher"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.catcher[0].arn
  desired_count   = 1
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.app_subnet_ids
    security_groups  = [var.security_groups["catcher"]]
    assign_public_ip = false
  }

  service_registries {
    registry_arn = aws_service_discovery_service.mail[0].arn
  }

  load_balancer {
    target_group_arn = var.target_groups["catcher"]
    container_name   = "catcher"
    container_port   = 8025
  }
}
