variable "project_name" {
  type    = string
  default = "solutions-copilot"
}

variable "region" {
  type    = string
  default = "us-east-1"
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "az_count" {
  type    = number
  default = 2
}

variable "db_name" {
  type    = string
  default = "copilot"
}

variable "db_username" {
  type    = string
  default = "copilot"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro" # Graviton (ARM), lowest-cost tier
}

variable "container_cpu" {
  type    = number
  default = 512
}

variable "container_memory" {
  type    = number
  default = 1024
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "bedrock_chat_model" {
  type    = string
  default = "us.anthropic.claude-haiku-4-5-20251001-v1:0"
}
