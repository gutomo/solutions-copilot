# Phase 3 slice 5: CI identity for the eval workflow.
#
# Creates the GitHub Actions OIDC provider (idempotent: skip if your account
# already has one from another repo) and a role this repo's eval workflow can
# assume to call Bedrock. NO ECR / ECS / Secrets Manager permissions -- those
# belong to the separate deploy role in iam.tf. NO running resources: OIDC
# provider, IAM role, and inline policy are all free.
#
# After `terraform apply`, paste the `eval_role_arn` output into the GitHub
# repo variable EVAL_AWS_ROLE_ARN to flip on the eval workflow's `if:` guard.

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

# GitHub Actions OIDC provider. AWS now trusts this issuer's chain natively, so
# the thumbprint list is belt-and-braces; both known thumbprints are included
# to survive a single rotation event without manual intervention.
resource "aws_iam_openid_connect_provider" "github" {
  count          = var.create_github_oidc_provider ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "1c58a3a8518e8759bf075b76b750d4f2df264fcd",
  ]
}

data "aws_iam_openid_connect_provider" "github" {
  count = var.create_github_oidc_provider ? 0 : 1
  url   = "https://token.actions.githubusercontent.com"
}

locals {
  github_oidc_arn = var.create_github_oidc_provider ? aws_iam_openid_connect_provider.github[0].arn : data.aws_iam_openid_connect_provider.github[0].arn
}

# ---- trust policy ---------------------------------------------------------
# Allowed identities:
#   - workflows on the main branch (push, schedule, workflow_dispatch)
#   - pull_request workflows from THIS repo (same-repo PRs only)
# Fork PRs cannot assume this role -- GitHub does not issue OIDC tokens with
# elevated sub claims to fork-PR workflow runs. This is the safe default.
data "aws_iam_policy_document" "eval_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.github_oidc_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repo}:ref:refs/heads/main",
        "repo:${var.github_repo}:pull_request",
      ]
    }
  }
}

resource "aws_iam_role" "eval" {
  name               = "solutions-copilot-eval"
  description        = "GitHub Actions OIDC role for the eval workflow. Bedrock-only; no ECR/ECS."
  assume_role_policy = data.aws_iam_policy_document.eval_assume.json
}

# ---- permissions policy ---------------------------------------------------
# Least-privilege: only InvokeModel + the streaming variant; only on the three
# foundation models + the two us. inference profiles the eval actually uses;
# us-east-1 only for the workflow's invoke point. Region nuance: us.* inference
# profiles route across us-east-1/us-east-2/us-west-2 and IAM checks
# InvokeModel on the foundation-model in the DESTINATION region, so the
# foundation-model ARNs must be region-wildcarded. The inference-profile ARNs
# stay pinned to us-east-1 because that's where the workflow invokes them.
# Titan v2 is invoked directly with no inference profile, so it's us-east-1
# only too. NO bedrock:ListFoundationModels / GetFoundationModel -- the eval
# only invokes.
data "aws_iam_policy_document" "eval_bedrock" {
  statement {
    sid    = "InvokeModels"
    effect = "Allow"
    actions = [
      "bedrock:InvokeModel",
      "bedrock:InvokeModelWithResponseStream",
    ]
    resources = [
      # Subject: Haiku 4.5 -- region wildcard for cross-region routing.
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-haiku-4-5-*",
      # Subject inference profile -- pinned to us-east-1 (where invoked).
      "arn:aws:bedrock:us-east-1::inference-profile/us.anthropic.claude-haiku-4-5-*",
      "arn:aws:bedrock:us-east-1:${data.aws_caller_identity.current.account_id}:inference-profile/us.anthropic.claude-haiku-4-5-*",

      # Judge: Sonnet 4.6 -- region wildcard for cross-region routing.
      "arn:aws:bedrock:*::foundation-model/anthropic.claude-sonnet-4-6*",
      # Judge inference profile -- pinned to us-east-1.
      "arn:aws:bedrock:us-east-1::inference-profile/us.anthropic.claude-sonnet-4-6*",
      "arn:aws:bedrock:us-east-1:${data.aws_caller_identity.current.account_id}:inference-profile/us.anthropic.claude-sonnet-4-6*",

      # Embeddings: Titan Text v2 -- direct invoke, no inference profile.
      "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-text-v2*",
    ]
  }
}

resource "aws_iam_role_policy" "eval_bedrock" {
  name   = "eval-bedrock-invoke"
  role   = aws_iam_role.eval.id
  policy = data.aws_iam_policy_document.eval_bedrock.json
}

# ---- outputs --------------------------------------------------------------
output "eval_role_arn" {
  value       = aws_iam_role.eval.arn
  description = <<-EOT
    Paste this into the GitHub repo variable EVAL_AWS_ROLE_ARN to activate the
    eval workflow. The workflow's `if:` guard reads that variable; when it's
    set, runs fire on PR + nightly cron + workflow_dispatch.
  EOT
}
