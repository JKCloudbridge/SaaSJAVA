# The data layer of one environment (Sprint 33, ADR-0293): the database, the cache and the object store. Nothing here is
# reachable from the internet; the database and cache sit in subnets with no route out and accept only the security groups of
# the application and the migration job.

locals {
  name = "platform-${var.environment}"
}

# ---- the database ----

resource "aws_db_subnet_group" "main" {
  name       = "${local.name}-db"
  subnet_ids = var.data_subnet_ids
}

resource "aws_db_parameter_group" "main" {
  name   = "${local.name}-postgres"
  family = "postgres${var.db_engine_version}"

  # Every connection is encrypted; a client that does not ask for it is refused.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # The server log may be read by an operator. Constraint errors quote the value in their own text, which the server cannot
  # be asked to leave out, so access to this log is restricted and its retention short (runbooks/incident.md); what can be
  # kept out is kept out: no parameter values next to a slow statement or an error.
  parameter {
    name  = "log_parameter_max_length"
    value = "0"
  }

  parameter {
    name  = "log_parameter_max_length_on_error"
    value = "0"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "2000"
  }
}

resource "aws_db_instance" "main" {
  identifier     = "${local.name}-db"
  engine         = "postgres"
  engine_version = var.db_engine_version
  instance_class = var.db_instance_class

  db_name  = "platform"
  username = "platform_owner" # the owner role: runs the migrations (the migration job) and manual migration M001 and M002

  # The owner's password is generated and kept by the provider's secret store; it is never in a file, in this plan, or in
  # the state. The application role platform_app is made by manual migration M001 and its password is set out of band.
  manage_master_user_password = true

  storage_type          = "gp3"
  allocated_storage     = var.db_allocated_storage_gb
  max_allocated_storage = var.db_max_allocated_storage_gb
  storage_encrypted     = true

  multi_az               = var.db_multi_az
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [var.database_security_group_id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.main.name

  backup_retention_period   = var.db_backup_retention_days
  backup_window             = "02:00-03:00"
  maintenance_window        = "sun:03:30-sun:04:30"
  copy_tags_to_snapshot     = true
  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = !var.db_keep_final_snapshot
  final_snapshot_identifier = var.db_keep_final_snapshot ? "${local.name}-db-final" : null

  auto_minor_version_upgrade      = true
  apply_immediately               = false
  performance_insights_enabled    = true
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
}

# ---- the cache (the sign-in and API limits that every instance must share) ----

resource "aws_elasticache_subnet_group" "main" {
  name       = "${local.name}-cache"
  subnet_ids = var.data_subnet_ids
}

# The cache password is generated here and written to the secret store; it reaches the application as a secret, not as text.
# It is in the state of this environment, so the state bucket is encrypted, private and read only by the owner (README).
resource "random_password" "cache" {
  length  = 40
  special = false
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "${local.name}-cache"
  description          = "Rate limits and sign-in counters of ${local.name}"

  engine         = "redis"
  engine_version = var.cache_engine_version
  node_type      = var.cache_node_type
  port           = 6379

  num_cache_clusters         = 1 + var.cache_replicas
  automatic_failover_enabled = var.cache_replicas > 0
  multi_az_enabled           = var.cache_replicas > 0

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [var.cache_security_group_id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.cache.result

  # The cache holds only counters that expire; losing it degrades the limits for a moment and nothing else (ADR-0021).
  snapshot_retention_limit = 0
  apply_immediately        = false
}

# ---- the object store of files ----

# force_destroy is left at its default (false): a bucket that still holds files, or earlier versions of them, cannot be
# removed by a destroy; the objects have to be removed first, on purpose.
resource "aws_s3_bucket" "files" {
  bucket = var.files_bucket_name
}

resource "aws_s3_bucket_public_access_block" "files" {
  bucket                  = aws_s3_bucket.files.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "files" {
  bucket = aws_s3_bucket.files.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "files" {
  bucket = aws_s3_bucket.files.id

  rule {
    id     = "earlier-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.files_noncurrent_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "files_tls_only" {
  statement {
    sid       = "DenyUnencryptedTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.files.arn, "${aws_s3_bucket.files.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "files" {
  bucket     = aws_s3_bucket.files.id
  policy     = data.aws_iam_policy_document.files_tls_only.json
  depends_on = [aws_s3_bucket_public_access_block.files]
}
