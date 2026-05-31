#requires -Version 5.1
#
# Phase 3 slice 1 -- eval harness launcher (PowerShell equivalent of run-eval.sh).
#
# Starts a DEDICATED throwaway pgvector sidecar (database copilot_eval) and runs
# the @Tag("eval") harness inside a JDK-21 Maven container on a shared docker
# network. Never touches the dev copilot-pg (5432/copilot). Bedrock calls use the
# Tokyo region + jp. Haiku profile by default (override via env).
#
# Run from the repo root:
#     powershell -ExecutionPolicy Bypass -File .\run-eval.ps1
#   (or just  .\run-eval.ps1  if your execution policy already allows local scripts)
#
# Stronger judge:  $env:EVAL_JUDGE_MODEL = 'jp.anthropic.claude-sonnet-4-6-...-v1:0'; .\run-eval.ps1
# Report lands at: app\target\eval-report.json  and  app\target\eval-report.md
# Requires Docker Desktop with the repo's drive shared (Settings -> Resources -> File sharing).
#
$PSNativeCommandUseErrorActionPreference = $false   # native non-zero exit must not throw (readiness loop)

$Network    = 'eval-net'
$Pg         = 'eval-pg'
$PgImage    = 'pgvector/pgvector:pg16'
$MavenImage = 'maven:3.9-eclipse-temurin-21'

$AwsRegion  = if ($env:AWS_REGION)         { $env:AWS_REGION }         else { 'ap-northeast-1' }
$ChatModel  = if ($env:BEDROCK_CHAT_MODEL) { $env:BEDROCK_CHAT_MODEL } else { 'jp.anthropic.claude-haiku-4-5-20251001-v1:0' }
$JudgeModel = if ($env:EVAL_JUDGE_MODEL)   { $env:EVAL_JUDGE_MODEL }   else { 'jp.anthropic.claude-haiku-4-5-20251001-v1:0' }
$AwsDir     = if ($env:AWS_DIR)            { $env:AWS_DIR }            else { Join-Path $env:USERPROFILE '.aws' }

# Slice 4 dashboard: pass the host's git short-SHA into the container so the
# report header + history aren't permanently "unknown". git is not installed
# in the maven container, so env-injection is the only path that works.
if ($env:GIT_SHA) {
  $GitSha = $env:GIT_SHA
} else {
  $GitSha = (git rev-parse --short HEAD 2>$null)
  if (-not $GitSha) { $GitSha = 'unknown' }
}

if ($PSScriptRoot) { Set-Location $PSScriptRoot }

Write-Host '==> recreating eval sidecar + network (dev copilot-pg is untouched)'
docker rm -f $Pg 2>$null | Out-Null
docker network create $Network 2>$null | Out-Null

Write-Host "==> starting $Pg ($PgImage) on $Network; host port 5433 for psql"
docker run -d --name $Pg --network $Network `
  -e POSTGRES_DB=copilot_eval -e POSTGRES_USER=copilot -e POSTGRES_PASSWORD=copilot `
  -p 5433:5432 $PgImage | Out-Null

Write-Host -NoNewline '==> waiting for postgres '
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Seconds 1
  docker exec $Pg pg_isready -U copilot -d copilot_eval *> $null
  if ($LASTEXITCODE -eq 0) { $ready = $true; break }
  Write-Host -NoNewline '.'
}
if (-not $ready) { Write-Host ''; Write-Host "postgres did not become ready; check: docker logs $Pg" -ForegroundColor Red; exit 1 }
Write-Host 'ready'

if (-not (Test-Path $AwsDir)) {
  Write-Host "!! WARNING: $AwsDir not found; Bedrock calls may fail (set `$env:AWS_DIR or export AWS_* env vars)" -ForegroundColor Yellow
}

Write-Host "==> running eval (mvn test -Peval) in $MavenImage on $Network"
Write-Host "    subject=$ChatModel"
Write-Host "    judge=$JudgeModel  region=$AwsRegion"
docker run --rm --network $Network `
  -v "${PWD}\app:/app" -w /app `
  -v "${HOME}\.m2:/root/.m2" `
  -v "${AwsDir}:/root/.aws:ro" `
  -e DB_HOST=$Pg -e DB_PORT=5432 -e DB_NAME=copilot_eval `
  -e DB_USERNAME=copilot -e DB_PASSWORD=copilot `
  -e AWS_REGION=$AwsRegion `
  -e AWS_PROFILE `
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN `
  -e BEDROCK_CHAT_MODEL=$ChatModel `
  -e EVAL_JUDGE_MODEL=$JudgeModel `
  -e GIT_SHA=$GitSha `
  $MavenImage `
  mvn test -Peval

Write-Host ''
if (-not (Test-Path 'app\target\eval-report.json')) {
  Write-Host '!! FAILED: app\target\eval-report.json was not produced.' -ForegroundColor Red
  Write-Host '   The eval test did not run -- check the -Peval profile / surefire output above.' -ForegroundColor Red
  Write-Host '   (Likely cause: the eval tag got excluded; verify the combine.self override in pom.xml.)' -ForegroundColor Red
  Write-Host "   eval-pg left running: docker exec -it $Pg psql -U copilot -d copilot_eval" -ForegroundColor Red
  exit 1
}

Write-Host '==> done. Report:'
Write-Host '      app\target\eval-report.json'
Write-Host '      app\target\eval-report.md'
Write-Host '      app\target\eval-report.html   (open in a browser)'
Write-Host '    history appended: app\eval-history.jsonl'
Write-Host "    inspect DB:  docker exec -it $Pg psql -U copilot -d copilot_eval"
Write-Host "    tear down:   docker rm -f $Pg; docker network rm $Network"
