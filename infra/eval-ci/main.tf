# Eval-CI identity root. Owns ONLY the GitHub Actions OIDC provider, the
# solutions-copilot-eval IAM role, and that role's Bedrock-only inline policy.
# No running resources -- all three are free IAM constructs.
#
# Moved out of the main infra/ root in Phase 3 cleanup so a bare `terraform
# apply` in infra/ no longer accidentally touches free eval-CI plumbing and
# vice versa. Resources were RELOCATED, not recreated -- the role ARN is
# unchanged, and the eval workflow's EVAL_AWS_ROLE_ARN repo variable continues
# to work.
#
# A future deploy-CI root that wants to use the same OIDC provider should
# `data` it (set its own create_github_oidc_provider = false and reference
# data.aws_iam_openid_connect_provider.github.arn) -- creating a duplicate
# would conflict with this root's resource.

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
