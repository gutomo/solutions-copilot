output "eval_role_arn" {
  value       = aws_iam_role.eval.arn
  description = <<-EOT
    Paste this into the GitHub repo variable EVAL_AWS_ROLE_ARN to activate the
    eval workflow. The workflow's `if:` guard reads that variable; when it's
    set, runs fire on PR + nightly cron + workflow_dispatch.
  EOT
}
