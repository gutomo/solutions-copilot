# Solutions Copilot — Phase 1 (RAG core)

An agentic enterprise knowledge copilot built on the JVM. This repository is
**Phase 1 (RAG core)**: a Spring Boot 3.4 + Spring AI 1.0.x service that
ingests documents, embeds them with Amazon Titan Text Embeddings v2 into a
pgvector store, and answers questions via a retrieval advisor that grounds
replies in the stored chunks and cites the source. Bedrock (Claude Haiku via
Converse) remains the chat model. The Phase 0 deploy substrate — ECS Fargate
on ARM64, RDS PostgreSQL 16, ALB, CI — carries over, with small additions for
the embedding path (IAM scope, env var).

```
React frontend (planned)
        |
   Spring Boot · Spring AI   <- agentic loop + tool calling (Phase 2)
     /      |       \
  RAG     tools    Bedrock (Claude Haiku 4.5 + Titan Embed v2 via Converse)
   |
 Postgres + pgvector
        |
 Cross-cutting: tracing · cost · CI evals · resilience (Phase 3–4)
```

## What's in this phase

- `app/` — Spring Boot service. `ChatClient` wired to Bedrock Converse with a
  `QuestionAnswerAdvisor` (Spring AI 1.0.x retrieval advisor) over a
  pgvector-backed `VectorStore`. Exposes `POST /api/chat` and
  `GET /api/chat/stream` — both now RAG-grounded — plus `POST /api/documents`
  for ingestion (chunk via `TokenTextSplitter`, embed with Titan v2, store).
  Flyway creates the `vector_store` table (`vector(1024)`, HNSW cosine index).
- `infra/` — Terraform for VPC, ECR, RDS PostgreSQL 16 (Graviton), Secrets
  Manager, IAM (task role allows Bedrock invoke for both Claude and Titan
  Embeddings v2), a public ALB, and an ECS Fargate (ARM64) service.
- `.github/workflows/deploy.yml` — build the ARM64 image, push to ECR, roll out.

## Prerequisites

1. **Enable Bedrock model access.** In the Bedrock console → Model access,
   request access to both (a) the Anthropic Claude model you intend to use
   and (b) **Amazon Titan Text Embeddings V2** (`amazon.titan-embed-text-v2:0`).
   These are separate per-model grants; missing the embedding grant breaks
   ingestion (and, downstream, retrieval-grounded chat) with `AccessDenied`.
2. Terraform ≥ 1.6, AWS credentials, Docker (with buildx) for a local push.
3. Confirm the **inference-profile ID** for your region in the Bedrock console
   (Model catalog → Inference profiles). The default is
   `us.anthropic.claude-haiku-4-5-20251001-v1:0`. Titan Embeddings v2 is a
   foundation model (no `us.`/`jp.` profile prefix); the same ID works in
   every region where it's enabled.

## Deploy

```bash
cd infra
cp terraform.tfvars.example terraform.tfvars   # edit if needed
terraform init
terraform apply
```

`apply` provisions everything and creates the ECS service. The service stays in
a pending state until an image exists in ECR — that's expected. Push the first
image:

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

# Ingest a document.
curl -X POST "$URL/api/documents" \
  -H 'content-type: application/json' \
  -d '{
    "source": "internal-policy-2026-Q2",
    "content": "Premier-tier reseller accounts have a CSP margin floor of 17.4 percent on Azure consumption, with a Hokkaido regional discount that lowers the floor to 14.9 percent for customers north of Sendai."
  }'
# -> {"source":"internal-policy-2026-Q2","chunks":1}

# Ask a question whose answer lives only in that document.
curl -X POST "$URL/api/chat" \
  -H 'content-type: application/json' \
  -d '{"message":"What is our CSP margin floor for Premier-tier resellers on Azure consumption? Cite the source."}'
# -> reply quotes 17.4 percent and cites internal-policy-2026-Q2

curl -N "$URL/api/chat/stream?message=Summarise%20Premier-tier%20Azure%20margin%20rules"
```

## Cost & teardown

There is no NAT gateway by design — the main standing costs are RDS (`db.t4g.micro`)
and the ALB. Tear everything down with:

```bash
cd infra && terraform destroy
```

## Notes

- **Auth.** `/api/chat`, `/api/chat/stream`, and `/api/documents` are public
  and unauthenticated. Ingestion is the more dangerous of the three because
  it persists content the retrieval advisor will later quote — don't expose
  the ALB beyond a smoke test until auth lands in Phase 4.
- **Japan data residency.** Deploy in `ap-northeast-1` and switch the chat
  model to a `jp.` inference profile (e.g.
  `jp.anthropic.claude-haiku-4-5-20251001-v1:0`) to keep inference within
  Japanese regions. Titan Embeddings v2 must be enabled separately in that
  region.
- **Rename.** Change the Maven `groupId`/`artifactId` and the Java package from
  `com.example.copilot` to your own before publishing.

## Next: Phase 2 (agentic tools)

Spring AI tool calling — a deterministic ROI calculator in Java, a `.docx`
proposal generator, task creation — plus a trace panel so you can see which
tools the model picked and why.
