# Solutions Copilot — Case Study

> An agentic enterprise RAG copilot on the JVM, built to show the engineering *around* an LLM feature — measured quality, graceful degradation, observability, and security — rather than the model call itself. This write-up is the "why it's good engineering" companion to the [README](../README.md).

## The problem

"Wire up an LLM" is easy; making one a feature you'd put in front of a B2B customer is not. The hard parts are everything around the call: **Is the answer actually grounded, and how would you know if it regressed? What happens when the model is throttled? What does a request cost, and where did the latency go? Who's allowed to call it? What if a retrieved document is hostile?**

Solutions Copilot is a deliberately scoped vehicle for answering those questions on the **JVM** (Java 21 · Spring Boot · Spring AI · Amazon Bedrock) — the stack a lot of enterprises actually run, and one where the AI tooling story is less worn-in than Python's. The domain is a cloud-reseller knowledge copilot (margins, Azure consumption, support SLAs); the content is synthetic so it can't be confused with the model's prior knowledge.

## Approach: small slices, proven before they're committed

Every capability was built as one tight slice — **plan → build → prove → commit** — and nothing merged until it was demonstrated working *and* failing correctly. The recurring rule, which turned out to be the spine of the whole project: **prove the negative.** A test that only ever passes proves very little; the interesting question is whether a gate fails *when it should*. That discipline is why the eval gate, the circuit breaker, the rate limiter, and the injection defense were each demonstrated degrading on purpose, and it's how several real bugs were caught (below).

The build moved through five phases: a Spring AI + Bedrock baseline, a RAG core (Titan embeddings → pgvector → a retrieval advisor that cites sources), agentic tool-calling, an eval harness wired into CI, and a production-hardening pass.

## What's technically notable

### 1. Measured quality — and a gate proven to fail

The differentiator is that answer quality is **measured and enforced**, not asserted. A golden-set harness scores every answer on faithfulness, answer-relevance, citation-correctness, and answer-correctness. The LLM-judged metrics use a *stronger* model than the subject (Sonnet judging Haiku) at temperature 0; the gated metrics are kept deterministic to avoid judging noise leaking into a CI pass/fail.

The corpus is seeded with **stale-vs-current distractors** — a superseded 2025 policy sitting next to the current one — so "retrieved the right chunk" isn't trivially true. And the gate is **regression-protected against itself**: a fixture test feeds it a known-bad report and asserts it goes red, so the fail-behavior is verified on every build rather than demonstrated once. The whole thing runs in GitHub Actions against a pgvector service container and blocks PRs that breach the thresholds in `eval-thresholds.yml`.

A subtle find here: the first LLM judge (the cheaper self-judge) scored answers that cited specific correct facts as "fabrication" — because the relevance judge was handed the answer with **no retrieved context** and got suspicious of specificity. Diagnosing that (the metric was measuring the wrong thing) and fixing it — feed the judge the context, decouple it from perceived factuality — was exactly the kind of "measure the measurement" work the project is about.

### 2. Cost accounting that understands the agentic loop

A single `/api/chat` turn can call the model more than once — the tool-calling loop calls it to decide on a tool, then again to answer with the tool's result. The naive approach (read token usage off the controller's final response) **undercounts** every agentic turn. Instead, cost is captured per model *call* via a Spring AI observation handler and summed per request, with a config-driven rate table and a loud warning (never a silent \$0) for an unrated model. Proving it meant showing a tool-invoking request reporting `calls=2` with the per-call token breakdown summing to the total — and catching a double-registration bug where the handler was counting every call twice.

### 3. The agentic loop as a distributed trace

Spring AI emits observations for the chat client, each model call, tool calls, and retrieval; bridging those to OpenTelemetry turns one request into a span tree — retrieval → model call → tool → model call — with token usage on each model span and the request's total cost tagged on the root. It defaults to `sampling=0` so the instrumentation is wired but inert without a collector (no network, no log spam, but trace IDs still flow into the logs for correlation). This observability then **paid for itself**: the stray 500 from an SSE async-dispatch bug (below) was first visible as an anomalous span.

### 4. Graceful degradation, proven to recover

The chat call is wrapped in a Resilience4j circuit breaker with an automatic **fallback to a second Bedrock model**. The proof is the full lifecycle, not a single fail-over: force the primary to fail, watch it fail over to the fallback (still grounded, still cost-accounted under the fallback model), watch the breaker open at the failure threshold, confirm it short-circuits while open (the primary isn't even called), then restore the primary and watch `HALF_OPEN → CLOSED`. Two details an interviewer would probe and that mattered: failures are classified by **walking the exception cause chain** (Spring AI wraps some AWS SDK exceptions, so matching the thrown type alone would silently never trip the breaker), and the aspect order is set so the breaker is *outer* and retry *inner* (one logical request = one breaker call). It also surfaced that `resilience4j-spring-boot3` doesn't pull the AOP weaver — without `spring-boot-starter-aop`, the `@CircuitBreaker` annotation is a silent no-op.

### 5. Auth, rate limiting, and the deploy-breaker avoided

API-key auth via Spring Security: stateless, CSRF off, constant-time key comparison, blank-credential rejection, no default-user backdoor. Per-key rate limiting returns 429 + `Retry-After`, keyed by the authenticated principal so anonymous traffic can't burn a key's quota. The detail that makes or breaks deployability: `/actuator/health` stays public so the load-balancer probe isn't locked out, while the rest of `/actuator` is protected. Rejected traffic (401/429) costs \$0 — it's turned away before the model call, and that's verified against the cost log. Building this also surfaced a genuinely subtle bug: SSE completion re-dispatches the request through the servlet filters (`ASYNC` dispatch), which double-counted a rate-limit permit and tried to write a 429 after the streaming response had committed — fixed by skipping `ASYNC`/`ERROR` dispatches and permitting the internal async dispatch.

### 6. Prompt-injection defense — measured, not claimed

Indirect prompt injection (a poisoned document steering the model through retrieved context) has no perfect fix, so the posture is layered + honest: an ingest-time scanner rejects documents matching injection idioms (qualifier-anchored to limit false positives), and the retrieved context is delimited with a hardened system prompt that treats it as untrusted data and drives tool calls only from the user's request. Crucially, resistance is **measured**: a deterministic `InjectionResistanceTest` ingests poisoned docs that slip the scanner and asserts a canary string is absent from the answer and that no coaxed tool fired (a clean DB-side-effect signal). The framing is explicit that these are *specific attacks held at bay*, not a proof of immunity.

## Engineering process

Beyond the slice discipline, two practices recur in the commit history:

- **Locked technical decisions.** A handful of choices that cost real debugging (exact Spring AI 1.0 artifact names, the embedding-model selector, the Flyway-owned `vector(1024)` schema, the no-AOP-on-tool-beans rule) are documented so they don't get "helpfully" reverted later.
- **Infra hygiene under pressure.** A near-miss — a bare `terraform apply` that would have stood up the whole prod stack when only a free IAM role was intended — was caught and reverted, then the eval-CI resources were moved into their own Terraform root (a zero-downtime state move, proven by `terraform plan` showing import/forget-only and the role ARN unchanged) so it can't recur.

## Outcomes

A working local system where: answers are grounded and cite sources; the agentic tools fire deterministically and are traced; quality is gated in CI with a dashboard; the service degrades to a fallback model and recovers; the API authenticates and throttles per client; and poisoned documents are blocked and resisted. Every one of those claims has a demonstration behind it, including the failure path.

## Honest limitations & what's next

- **Not deployed to AWS** — runs locally + a free eval-CI role; the full-stack Terraform is included but unapplied (a deliberate cost choice). The remaining roadmap item, moving ECS tasks to private subnets, is genuinely deploy-gated.
- **The eval corpus is small**, so faithfulness/retrieval scores are near-perfect; the harness is the asset, and a larger, harder corpus is the obvious next step to make those metrics discriminating.
- **Cost rates are illustrative** pending live Bedrock pricing; rate limiting is in-memory per instance (distributed/Redis is future work); resilience fails over to another Bedrock model, not another provider.
- **Injection defense is layered, not absolute.**

The throughline: an LLM feature is only as good as the engineering that surrounds it, and that engineering is only trustworthy if you can show it failing correctly.
