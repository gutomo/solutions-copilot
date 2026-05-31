terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }

  # Local state, same posture as the main infra/ root.
  # For team use, store state remotely (uncomment and configure):
  # backend "s3" {
  #   bucket = "your-tf-state-bucket"
  #   key    = "solutions-copilot/eval-ci/terraform.tfstate"
  #   region = "us-east-1"
  # }
}

provider "aws" {
  region = var.region

  # default_tags match infra/ exactly so the state move is byte-identical and
  # plan lands at "no changes" post-import. Don't add a Component tag here --
  # it would in-place-update tags_all on the imported resources, which would
  # show up in plan as ~ changes (not destroy/replace, but noisy).
  default_tags {
    tags = {
      Project   = "solutions-copilot"
      ManagedBy = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
