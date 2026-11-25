#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"

declare -A active_runs=()
active_run_ids="$(
  gh api --paginate \
    "repos/$GITHUB_REPOSITORY/actions/runs?status=in_progress&per_page=100" \
    --jq '.workflow_runs[].id'
)"
while IFS= read -r run_id; do
  [[ -n "$run_id" ]] && active_runs["$run_id"]=1
done <<< "$active_run_ids"

deleted=0
preserved=0
artifacts="$(
  gh api --paginate \
    "repos/$GITHUB_REPOSITORY/actions/artifacts?per_page=100" \
    --jq '.artifacts[] | select(.expired == false) | [.id, .workflow_run.id] | @tsv'
)"
while IFS=$'\t' read -r artifact_id run_id; do
  [[ -n "$artifact_id" ]] || continue
  if [[ -n "${active_runs[$run_id]:-}" ]]; then
    preserved=$((preserved + 1))
    continue
  fi

  if gh api --silent --method DELETE \
    "repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id" 2>/dev/null; then
    deleted=$((deleted + 1))
  fi
done <<< "$artifacts"

echo "Deleted $deleted completed-run artifact(s); preserved $preserved artifact(s) from active runs."
