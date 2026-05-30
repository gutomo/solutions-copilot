resource "random_password" "db" {
  length  = 24
  special = false # avoid characters that complicate JDBC URLs / shell escaping
}

resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.project_name}/db"
  recovery_window_in_days = 0 # immediate delete on destroy (portfolio convenience)
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.db_username
    password = random_password.db.result
  })
}
