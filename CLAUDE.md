# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is
An agentic enterprise "solutions copilot" built on the JVM — a portfolio project
demonstrating production-grade AI engineering. This repo is currently the **Phase 0
baseline** (a green deploy); later phases build the real product on top.

Architecture: React frontend → Spring Boot (Spring AI) orchestrator running an
agentic loop → RAG retrieval (Postgres/pgvector), agent tools, and Amazon Bedrock
(Claude via the Converse API). Cross-cutting: tracing, cost accounting, CI evals,
resilience.

## Repo layout
- `app/`   — Spring Boot 3.4 + Spring AI service (Java 21, Maven)
- `infra/` — Terraform: VPC, ECR, RDS Postgres (pgvector), ECS Fargate ARM64, ALB, IAM
- `.github/workflows/deploy.yml` — build ARM64 image → push to ECR → roll out ECS

## Commands
- Build:       `cd app && mvn -q clean package`
- Run locally: needs a local Postgres + AWS creds with Bedrock access, then
               `cd app && mvn spring-boot:run`
- Deploy:      `cd infra && terraform apply`, then push the image (see README)
- Smoke test:  `curl $ALB/actuator/health`, `POST /api/chat`, `GET /api/chat/stream`

## Conventions & hard constraints (do not change without being asked)
- Java 21, Spring Boot 3.4.x, Spring AI 1.0.x. Do NOT upgrade to Spring AI 2.x /
  Spring Boot 4 — that is a separate track and will break this build.
- The Bedrock dependency is `spring-ai-starter-model-bedrock-converse` (the post-1.0
  artifact name). Do not revert to the old `*-spring-boot-starter` name.
- Bedrock model IDs are cross-region inference profiles, e.g.
  `us.anthropic.claude-haiku-4-5-20251001-v1:0`. Keep the model in config
  (`BEDROCK_CHAT_MODEL`); never hardcode it in Java.
- AWS credentials come from the default provider chain (the ECS task role at
  runtime). Never add static access keys to config or code.
- Prefer constructor injection and Java records. Keep controllers thin; logic in
  services. Plain prose comments, no over-commenting.
- Infra stays cheap: no NAT gateway, tasks in public subnets, single-AZ RDS. Flag
  any change that adds meaningful cost before making it.

## Roadmap (what "next" means)
- Phase 1 — RAG core: add `spring-ai-starter-vector-store-pgvector` + an embedding
  model; build ingestion (chunk → embed → store); add a retrieval advisor so
  answers cite sources; stream to the UI.
- Phase 2 — Agentic tools: Spring AI tool calling; ROI calc (deterministic Java),
  proposal generator (.docx), task creation; a trace panel.
- Phase 3 — Eval harness in CI: golden Q&A set scored on faithfulness, answer
  relevance, and citation correctness; a quality dashboard.
- Phase 4 — Hardening: OpenTelemetry, per-request cost accounting, Resilience4j
  (circuit breaker + fallback model), auth + rate limiting, prompt-injection
  defense on ingested docs, move tasks to private subnets.

## Watch out for
- Bedrock returns `AccessDenied` until model access is granted in the Bedrock console.
- The chat endpoint is currently public and unauthenticated (auth is Phase 4) — do
  not expose it widely.
- `terraform apply` creates the ECS service before any image exists; it stabilises
  after the first image push. That pending state is expected, not a failure.
