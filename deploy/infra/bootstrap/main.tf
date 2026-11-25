# What has to exist before an environment can be planned: the places the state of each environment is kept, and the registry
# the images are pushed to (Sprint 33, ADR-0292). Run ONCE, by the owner, from this folder, with a local state file that the owner
# keeps (it only lists these few resources). Nothing in here holds any data of the platform.
#
#   tofu init
#   tofu plan  -var region=<region> -var state_bucket_suffix=<something unique>
#   tofu apply -var region=<region> -var state_bucket_suffix=<something unique>
#
# The two state buckets are separate (staging and production never share one), private, encrypted, versioned, and refuse
# plain-text transport. The registry is the one thing shared by every environment, on purpose: an image is built once and
# promoted, never rebuilt per environment (ADR-0287). The environments only pull from it.

terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "region" {
  type = string
}

variable "state_bucket_suffix" {
  description = "Makes the bucket names globally unique."
  type        = string
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "platform"
      ManagedBy = "infrastructure-files"
    }
  }
}

# ---- the state buckets ----

locals {
  environments = ["staging", "production"]
}

resource "aws_s3_bucket" "state" {
  for_each = toset(local.environments)

  bucket = "platform-tfstate-${each.key}-${var.state_bucket_suffix}"
}

resource "aws_s3_bucket_public_access_block" "state" {
  for_each = aws_s3_bucket.state

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "state" {
  for_each = aws_s3_bucket.state

  bucket = each.value.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  for_each = aws_s3_bucket.state

  bucket = each.value.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

data "aws_iam_policy_document" "state_tls_only" {
  for_each = aws_s3_bucket.state

  statement {
    sid       = "DenyUnencryptedTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [each.value.arn, "${each.value.arn}/*"]

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

resource "aws_s3_bucket_policy" "state" {
  for_each = aws_s3_bucket.state

  bucket     = each.value.id
  policy     = data.aws_iam_policy_document.state_tls_only[each.key].json
  depends_on = [aws_s3_bucket_public_access_block.state]
}

# ---- the registry: one repository per image, tags that can never be moved, a scan on every push ----

resource "aws_ecr_repository" "image" {
  for_each = toset(["platform-api", "platform-web", "platform-edge"])

  name                 = each.key
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

resource "aws_ecr_lifecycle_policy" "image" {
  for_each = aws_ecr_repository.image

  repository = each.value.name

  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 60 images; a release that is still running is always among them"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 60
      }
      action = { type = "expire" }
    }]
  })
}

output "state_buckets" {
  value = { for environment, bucket in aws_s3_bucket.state : environment => bucket.bucket }
}

output "registry_repositories" {
  value = { for name, repository in aws_ecr_repository.image : name => repository.repository_url }
}
