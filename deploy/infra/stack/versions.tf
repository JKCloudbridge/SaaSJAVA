terraform {
  # Works with Terraform and with OpenTofu (the same files and the same commands; ADR-0292).
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # The state of each environment is kept in its own object in a private, encrypted, versioned bucket, with a lock so two
  # people cannot change the environment at once. The names are in values/<environment>.backend.hcl:
  #   tofu init -backend-config=values/staging.backend.hcl
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "platform"
      Environment = var.environment
      ManagedBy   = "infrastructure-files"
    }
  }
}
