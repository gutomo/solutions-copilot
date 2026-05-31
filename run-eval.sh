#!/usr/bin/env bash
#
# Phase 3 slice 1 -- eval harness launcher.
#
# Starts a DEDICATED throwaway pgvector sidecar (database copilot_eval) and runs
# the @Tag("eval") harness inside a JDK-21 Maven container on a shared docker
# network. It never touches the dev copilot-pg (5432/copilot). Bedrock calls use
# the Tokyo region + jp. Haiku profile by default (override via env).
#
# Usage:           ./run-eval.sh
# Stronger judge:  EVAL_JUDGE_MODEL=jp.anthropic.claude-sonnet-4-6-...-v1:0 ./run-eval.sh
# Report lands at: app/target/eval-report.json  and  app/target/eval-report.md
#
# Note (Git Bash on Windows): if Docker complains about mangled in-container
# paths (/app, /root/.m2), re-run prefixed with  MSYS_NO_PATHCONV=1 ./run-eval.sh
#
set -euo pipefail

NETWORK=eval-net
PG=eval-pg
PG_IMAGE=pgvector/pgvector:pg16
MAVEN_IMAGE=maven:3.9-eclipse-temurin-21

AWS_REGION="${AWS_REGION:-ap-northeast-1}"
BEDROCK_CHAT_MODEL="${BEDROCK_CHAT_MODEL:-jp.anthropic.claude-haiku-4-5-20251001-v1:0}"
EVAL_JUDGE_MODEL="${EVAL_JUDGE_MODEL:-jp.anthropic.claude-haiku-4-5-20251001-v1:0}"
AWS_DIR="${AWS_DIR:-$HOME/.aws}"

# Slice 4 dashboard: pass the host's git short-SHA into the container so the
# report header + history aren't permanently "unknown". git is not installed
# in the maven container, so env-injection is the only path that works.
GIT_SHA="${GIT_SHA:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}"

cd "$(dirname "$0")"

echo "==> recreating eval sidecar + network (dev copilot-pg is untouched)"
docker rm -f "$PG" >/dev/null 2>&1 || true
docker network create "$NETWORK" >/dev/null 2>&1 || true

echo "==> starting $PG ($PG_IMAGE) on $NETWORK; host port 5433 for psql"
docker run -d --name "$PG" --network "$NETWORK" \
  -e POSTGRES_DB=copilot_eval -e POSTGRES_USER=copilot -e POSTGRES_PASSWORD=copilot \
  -p 5433:5432 "$PG_IMAGE" >/dev/null

echo -n "==> waiting for postgres "
until docker exec "$PG" pg_isready -U copilot -d copilot_eval >/dev/null 2>&1; do
  echo -n "."
  sleep 1
done
echo "ready"

[ -d "$AWS_DIR" ] || echo "!! WARNING: $AWS_DIR not found; Bedrock calls may fail (set AWS_DIR=... or export AWS_* env vars)" >&2

echo "==> running eval (mvn test -Peval) in $MAVEN_IMAGE on $NETWORK"
echo "    subject=$BEDROCK_CHAT_MODEL"
echo "    judge=$EVAL_JUDGE_MODEL  region=$AWS_REGION"
docker run --rm --network "$NETWORK" \
  -v "$PWD/app":/app -w /app \
  -v "$HOME/.m2":/root/.m2 \
  -v "$AWS_DIR":/root/.aws:ro \
  -e DB_HOST="$PG" -e DB_PORT=5432 -e DB_NAME=copilot_eval \
  -e DB_USERNAME=copilot -e DB_PASSWORD=copilot \
  -e AWS_REGION="$AWS_REGION" \
  -e AWS_PROFILE \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
  -e BEDROCK_CHAT_MODEL="$BEDROCK_CHAT_MODEL" \
  -e EVAL_JUDGE_MODEL="$EVAL_JUDGE_MODEL" \
  -e GIT_SHA="$GIT_SHA" \
  "$MAVEN_IMAGE" \
  mvn test -Peval

echo ""
# Post-condition: the report file proves the eval actually ran. Surefire does
# NOT fail when zero tests match, so if the -Peval profile silently selected
# nothing (e.g. the combine.self override regressed), this is what catches it.
if [ ! -f app/target/eval-report.json ]; then
  echo "!! FAILED: app/target/eval-report.json was not produced." >&2
  echo "   The eval test did not run -- check the -Peval profile / surefire output above." >&2
  echo "   (Likely cause: the eval tag got excluded; verify the combine.self override in pom.xml.)" >&2
  echo "   eval-pg left running for inspection: docker exec -it $PG psql -U copilot -d copilot_eval" >&2
  exit 1
fi

echo "==> done. Report:"
echo "      app/target/eval-report.json"
echo "      app/target/eval-report.md"
echo "      app/target/eval-report.html   (open in a browser)"
echo "    history appended: app/eval-history.jsonl"
echo "    inspect DB:  docker exec -it $PG psql -U copilot -d copilot_eval"
echo "    tear down:   docker rm -f $PG && docker network rm $NETWORK"
