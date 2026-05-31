variable "region" {
  type        = string
  default     = "us-east-1"
  description = "AWS region for the eval-CI provider. The eval workflow invokes Bedrock in us-east-1; only the OIDC provider + IAM role + inline policy are managed here, all of which are global IAM, so this region mainly controls the SDK endpoint."
}

variable "create_github_oidc_provider" {
  type        = bool
  default     = true
  description = <<-EOT
    Set to false if your AWS account already has the GitHub Actions OIDC
    provider from another repo. Find an existing one with:
        aws iam list-open-id-connect-providers
    Terraform will then attach the role to the existing provider via the
    aws_iam_openid_connect_provider data source instead of trying to create a
    duplicate (which would fail with EntityAlreadyExists).
  EOT
}

variable "github_repo" {
  type        = string
  default     = "gutomo/solutions-copilot"
  description = <<-EOT
    owner/repo for the OIDC trust policy's `sub` allowlist. Change if you fork
    -- the role is scoped to THIS repo only so a different repo's CI cannot
    assume it.
  EOT
}
