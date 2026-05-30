resource "aws_ecr_repository" "app" {
  name                 = var.project_name
  image_tag_mutability = "MUTABLE"
  force_delete         = true # convenient for a teardown-friendly portfolio repo

  image_scanning_configuration {
    scan_on_push = true
  }
}
