# The edge of one environment (Sprint 33, ADR-0286): the load balancer, the wildcard certificate, the DNS names, and the rules
# that send /api to the application and everything else to the web server. The host name is the tenant, so this is a security
# component: it keeps the original Host header, redirects http to https, sends HSTS, and never routes the management port.
# What the load balancer cannot do (bound a chunked body, overwrite X-Forwarded-For, drop X-Forwarded-Host) is done by the
# edge sidecar of each application task (deploy/edge), which is why the target of /api is the sidecar.

locals {
  name = "platform-${var.environment}"
}

data "aws_route53_zone" "platform" {
  name         = var.dns_zone_name
  private_zone = false
}

# ---- the certificate: the domain and every name under it, renewed by the provider ----

resource "aws_acm_certificate" "platform" {
  domain_name               = var.platform_domain
  subject_alternative_names = ["*.${var.platform_domain}"]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "validation" {
  for_each = {
    for option in aws_acm_certificate.platform.domain_validation_options : option.domain_name => {
      name   = option.resource_record_name
      record = option.resource_record_value
      type   = option.resource_record_type
    }
  }

  zone_id         = data.aws_route53_zone.platform.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true # the domain and its wildcard share one validation record
}

resource "aws_acm_certificate_validation" "platform" {
  certificate_arn         = aws_acm_certificate.platform.arn
  validation_record_fqdns = [for record in aws_route53_record.validation : record.fqdn]
}

# ---- the load balancer ----

resource "aws_lb" "main" {
  name               = local.name
  load_balancer_type = "application"
  internal           = false
  security_groups    = [var.balancer_security_group_id]
  subnets            = var.public_subnet_ids

  idle_timeout               = var.idle_timeout_seconds
  enable_http2               = true
  enable_deletion_protection = var.deletion_protection
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  preserve_host_header       = true # the host is the tenant: it must reach the application exactly as the caller wrote it
  xff_header_processing_mode = "append"
  enable_xff_client_port     = false
}

resource "aws_lb_target_group" "api" {
  name                          = "${local.name}-api"
  vpc_id                        = var.vpc_id
  port                          = 8080 # the edge sidecar
  protocol                      = "HTTP"
  target_type                   = "ip"
  deregistration_delay          = var.deregistration_delay_seconds
  load_balancing_algorithm_type = "least_outstanding_requests"

  # The sidecar answers /edge-ready with the application's own readiness: a task that is draining, or whose sidecar is down,
  # leaves rotation after unhealthy_threshold checks.
  health_check {
    path                = "/edge-ready"
    port                = "traffic-port"
    matcher             = "200"
    interval            = var.health_check_interval_seconds
    timeout             = 3
    healthy_threshold   = 2
    unhealthy_threshold = var.unhealthy_threshold
  }
}

resource "aws_lb_target_group" "web" {
  name                          = "${local.name}-web"
  vpc_id                        = var.vpc_id
  port                          = 3000
  protocol                      = "HTTP"
  target_type                   = "ip"
  deregistration_delay          = var.deregistration_delay_seconds
  load_balancing_algorithm_type = "least_outstanding_requests"

  health_check {
    path                = "/healthz"
    port                = "traffic-port"
    matcher             = "200"
    interval            = var.health_check_interval_seconds
    timeout             = 3
    healthy_threshold   = 2
    unhealthy_threshold = var.unhealthy_threshold
  }
}

resource "aws_lb_target_group" "catcher" {
  count = var.mail_catcher ? 1 : 0

  name        = "${local.name}-mail"
  vpc_id      = var.vpc_id
  port        = 8025
  protocol    = "HTTP"
  target_type = "ip"

  health_check {
    path    = "/readyz"
    matcher = "200"
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
  certificate_arn   = aws_acm_certificate_validation.platform.certificate_arn

  # Browsers are told to use https for a year, on the platform and on every organization under it.
  routing_http_response_strict_transport_security_header_value = "max-age=31536000; includeSubDomains"
  routing_http_response_server_enabled                         = false

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn
  }
}

# The management endpoints are never served to the outside, whatever reaches them (they are not on a routed port either).
resource "aws_lb_listener_rule" "management_never" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 5

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }

  condition {
    path_pattern {
      values = ["/actuator", "/actuator/*"]
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }
}

# A test environment's mail catcher: its screen is shown only to the operators' addresses; everyone else gets the same 404
# as for a name that does not exist.
resource "aws_lb_listener_rule" "catcher_operators" {
  count = var.mail_catcher && length(var.operator_cidrs) > 0 ? 1 : 0

  listener_arn = aws_lb_listener.https.arn
  priority     = 20

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.catcher[0].arn
  }

  condition {
    host_header {
      values = ["mail.${var.platform_domain}"]
    }
  }

  condition {
    source_ip {
      values = var.operator_cidrs
    }
  }
}

resource "aws_lb_listener_rule" "catcher_others" {
  count = var.mail_catcher ? 1 : 0

  listener_arn = aws_lb_listener.https.arn
  priority     = 21

  action {
    type = "fixed-response"

    fixed_response {
      content_type = "text/plain"
      message_body = "Not found"
      status_code  = "404"
    }
  }

  condition {
    host_header {
      values = ["mail.${var.platform_domain}"]
    }
  }
}

# ---- the names ----

resource "aws_route53_record" "platform" {
  for_each = toset([var.platform_domain, "*.${var.platform_domain}"])

  zone_id = data.aws_route53_zone.platform.zone_id
  name    = each.value
  type    = "A"

  alias {
    name                   = aws_lb.main.dns_name
    zone_id                = aws_lb.main.zone_id
    evaluate_target_health = true
  }
}
