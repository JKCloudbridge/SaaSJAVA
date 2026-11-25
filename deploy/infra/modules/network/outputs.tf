output "vpc_id" {
  value = aws_vpc.main.id
}

output "vpc_cidr" {
  description = "The network's range: the edge sidecar treats the load balancer's nodes inside it as the trusted hop."
  value       = aws_vpc.main.cidr_block
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "app_subnet_ids" {
  value = aws_subnet.app[*].id
}

output "data_subnet_ids" {
  value = aws_subnet.data[*].id
}

output "nat_addresses" {
  description = "The fixed addresses every outbound call of this environment leaves from."
  value       = aws_eip.nat[*].public_ip
}

output "security_groups" {
  value = {
    balancer = aws_security_group.balancer.id
    api      = aws_security_group.api.id
    web      = aws_security_group.web.id
    migrate  = aws_security_group.migrate.id
    catcher  = aws_security_group.catcher.id
    database = aws_security_group.database.id
    cache    = aws_security_group.cache.id
  }
}
