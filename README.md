# Solutions Copilot — Phase 0 baseline

An agentic enterprise knowledge copilot built on the JVM. This repository is the
**Phase 0 baseline**: a Spring Boot 3.4 + Spring AI service that talks to Amazon
Bedrock, backed by Postgres (pgvector enabled), deployed to ECS Fargate on ARM64
entirely via Terraform, with a GitHub Actions pipeline. The goal of this phase is
a **green deploy** — real infrastructure to build the rest of the system on, not a
notebook.

```
React frontend (Phase 1)
        |
   Spring Boot · Spring AI   <- agentic loop + tool calling (Phase 2)
     /      |       \
  RAG     tools    Bedrock (Claude Haiku 4.5 via Converse)
   |
 Postgres + pgvector
        |
 Cross-cutting: tracing · cost · CI evals · resilience (Phase 3–4)
```

## What's in this phase

- `app/` — Spring Boot service. A `ChatClient` wired to Bedrock Converse, exposing
  `POST /api/chat` (blocking) and `GET /api/chat/stream` (SSE). Flyway enables the
  `vector` extension and creates an illustrative `documents` table.
- `infra/` — Terraform for VPC, ECR, RDS PostgreSQL 16 (Graviton), Secrets Manager,
  IAM (task role scoped to Bedrock invoke), a public ALB, and an ECS Fargate
  (ARM64) service.
- `.github/workflows/deploy.yml` — build the ARM64 image, push to ECR, roll out.

## Prerequisites

1. **Enable Bedrock model access.** In the Bedrock console → Model access, request
   access to the Anthropic Claude model you intend to use. Without this, invocations
   return `AccessDenied`.
2. Terraform ≥ 1.6, AWS credentials, Docker (with buildx) for a local push.
3. Confirm the **inference-profile ID** for your region in the Bedrock console
   (Model catalog → Inference profiles). The default is
   `us.anthropic.claude-haiku-4-5-20251001-v1:0`.

## Deploy

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # edit if needed
terraform init
terraform apply
```

`apply` provisions everything and creates the ECS service. The service stays in a
pending state until an image exists in ECR — that's expected. Push the first image:

```bash
# from the repo root. Set REGION to match your terraform.tfvars.
REGION=us-east-1
ECR=$(cd infra && terraform output -raw ecr_repository_url)

aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "${ECR%/*}"

docker buildx build --platform linux/arm64 -t "$ECR:latest" --push ./app

aws ecs update-service \
  --cluster "$(cd infra && terraform output -raw ecs_cluster_name)" \
  --service "$(cd infra && terraform output -raw ecs_service_name)" \
  --force-new-deployment
```

After this, CI handles subsequent pushes on every commit to `main`.

## Test

```bash
URL=$(cd infra && terraform output -raw alb_url)

curl "$URL/actuator/health"          # {"status":"UP"}

curl -X POST "$URL/api/chat" \
  -H 'content-type: application/json' \
  -d '{"message":"In one sentence, what is a CSP reseller?"}'

curl -N "$URL/api/chat/stream?message=Say%20hello%20token%20by%20token"
```

## Cost & teardown

There is no NAT gateway by design — the main standing costs are RDS (`db.t4g.micro`)
and the ALB. Tear everything down with:

```bash
cd infra && terraform destroy
```

## Notes

- **Auth.** The chat endpoint is public and unauthenticated in this baseline. Don't
  leave it exposed long; authentication and rate limiting land in Phase 4.
- **Japan data residency.** Deploy in `ap-northeast-1` and switch the model to a
  `jp.` inference profile (e.g. `jp.anthropic.claude-haiku-4-5-20251001-v1:0`) to keep
  inference within Japanese regions.
- **Rename.** Change the Maven `groupId`/`artifactId` and the Java package from
  `com.example.copilot` to your own before publishing.

## Next: Phase 1 (RAG core)

Add `org.springframework.ai:spring-ai-starter-vector-store-pgvector` and an
embedding model, build the ingestion pipeline (chunk → embed → store), and wire a
retrieval advisor into the `ChatClient` so answers cite their sources.
