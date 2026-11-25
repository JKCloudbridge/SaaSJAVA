# The network of one environment (Sprint 33, ADR-0292, ADR-0293). Three kinds of subnet in two zones:
#   public   only the load balancer and the NAT gateways; nothing else has a public address
#   app      the application, the web server and the jobs; they reach the internet only through the NAT gateways, so every
#            outbound call leaves from the fixed addresses of the NAT (outputs: nat_addresses)
#   data     the database and the cache; they have no route to the internet at all, in or out

locals {
  name = "platform-${var.environment}"
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name }
}

resource "aws_subnet" "public" {
  count = 2

  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false

  tags = { Name = "${local.name}-public-${count.index}", Tier = "public" }
}

resource "aws_subnet" "app" {
  count = 2

  vpc_id            = aws_vpc.main.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 4)

  tags = { Name = "${local.name}-app-${count.index}", Tier = "app" }
}

resource "aws_subnet" "data" {
  count = 2

  vpc_id            = aws_vpc.main.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)

  tags = { Name = "${local.name}-data-${count.index}", Tier = "data" }
}

# The fixed outbound addresses: a customer who allows calls only from known addresses is told these (runbook
# outbound-addresses.md). They belong to the environment and survive a replaced NAT gateway.
resource "aws_eip" "nat" {
  count = var.nat_gateway_count

  domain = "vpc"

  tags = { Name = "${local.name}-nat-${count.index}" }
}

resource "aws_nat_gateway" "main" {
  count = var.nat_gateway_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags       = { Name = "${local.name}-${count.index}" }
  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = 2

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# One route table for each app subnet; with one NAT gateway both use it, with two each uses the one of its own zone.
resource "aws_route_table" "app" {
  count = 2

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[min(count.index, var.nat_gateway_count - 1)].id
  }

  tags = { Name = "${local.name}-app-${count.index}" }
}

resource "aws_route_table_association" "app" {
  count = 2

  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# The data subnets have a table of their own with no route out.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name}-data" }
}

resource "aws_route_table_association" "data" {
  count = 2

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# Files go to the object store over the provider's own network, not through the NAT gateways (no cost, no exposure).
data "aws_region" "current" {}

resource "aws_vpc_endpoint" "object_store" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${data.aws_region.current.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id

  tags = { Name = "${local.name}-object-store" }
}

# ---- security groups: who may talk to whom ----

resource "aws_security_group" "balancer" {
  name        = "${local.name}-balancer"
  description = "The load balancer: https and http from the internet"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-balancer" }
}

resource "aws_vpc_security_group_ingress_rule" "balancer_https" {
  security_group_id = aws_security_group.balancer.id
  description       = "https from anywhere"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "balancer_http" {
  security_group_id = aws_security_group.balancer.id
  description       = "http from anywhere (answered with a redirect to https)"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

# The application tasks. Port 8080 is the edge sidecar, taken from the load balancer only. The management port 8081 is not
# opened to anyone: it is reached inside the task (the metrics collector) and through the sidecar's one health path.
resource "aws_security_group" "api" {
  name        = "${local.name}-api"
  description = "Application tasks: the edge sidecar from the load balancer"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-api" }
}

resource "aws_vpc_security_group_ingress_rule" "api_from_balancer" {
  security_group_id            = aws_security_group.api.id
  description                  = "The edge sidecar, from the load balancer"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.balancer.id
}

resource "aws_security_group" "web" {
  name        = "${local.name}-web"
  description = "Web tasks: from the load balancer"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-web" }
}

resource "aws_vpc_security_group_ingress_rule" "web_from_balancer" {
  security_group_id            = aws_security_group.web.id
  description                  = "The web server, from the load balancer"
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  referenced_security_group_id = aws_security_group.balancer.id
}

# The migration job: it only needs to reach the database.
resource "aws_security_group" "migrate" {
  name        = "${local.name}-migrate"
  description = "The migration job: no inbound traffic"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-migrate" }
}

# A mail catcher (staging only): the application sends to it, the load balancer shows its screen.
resource "aws_security_group" "catcher" {
  name        = "${local.name}-mail-catcher"
  description = "Mail catcher of a test environment"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-mail-catcher" }
}

resource "aws_vpc_security_group_ingress_rule" "catcher_smtp" {
  security_group_id            = aws_security_group.catcher.id
  description                  = "Mail from the application"
  ip_protocol                  = "tcp"
  from_port                    = 1025
  to_port                      = 1025
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_vpc_security_group_ingress_rule" "catcher_screen" {
  security_group_id            = aws_security_group.catcher.id
  description                  = "The screen, from the load balancer (and only for the operators' addresses, by its listener rule)"
  ip_protocol                  = "tcp"
  from_port                    = 8025
  to_port                      = 8025
  referenced_security_group_id = aws_security_group.balancer.id
}

resource "aws_security_group" "database" {
  name        = "${local.name}-database"
  description = "The database: only the application tasks and the migration job"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-database" }
}

resource "aws_vpc_security_group_ingress_rule" "database_from_api" {
  security_group_id            = aws_security_group.database.id
  description                  = "The application role, from the application tasks"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.api.id
}

resource "aws_vpc_security_group_ingress_rule" "database_from_migrate" {
  security_group_id            = aws_security_group.database.id
  description                  = "The owner role, from the migration job"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.migrate.id
}

resource "aws_security_group" "cache" {
  name        = "${local.name}-cache"
  description = "The cache: only the application tasks"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${local.name}-cache" }
}

resource "aws_vpc_security_group_ingress_rule" "cache_from_api" {
  security_group_id            = aws_security_group.cache.id
  description                  = "From the application tasks"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.api.id
}

# Outbound: tasks and the balancer may call out (the balancer only to its targets; the tasks to the data layer, the object
# store and, through the NAT gateways, the internet). The data layer calls nothing.
resource "aws_vpc_security_group_egress_rule" "balancer_to_targets" {
  for_each = {
    api   = aws_security_group.api.id
    web   = aws_security_group.web.id
    mails = aws_security_group.catcher.id
  }

  security_group_id            = aws_security_group.balancer.id
  description                  = "To the ${each.key} targets"
  ip_protocol                  = "-1"
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_egress_rule" "tasks_out" {
  for_each = {
    api     = aws_security_group.api.id
    web     = aws_security_group.web.id
    migrate = aws_security_group.migrate.id
    catcher = aws_security_group.catcher.id
  }

  security_group_id = each.value
  description       = "Outbound calls leave through the NAT gateways"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
