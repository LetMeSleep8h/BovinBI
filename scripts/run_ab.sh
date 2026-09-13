#!/usr/bin/env bash
# A/B 对照评测:同一评测集,N 轮 × 双引擎,报告落盘 eval-runs/
# 用法:
#   LLM_API_KEY=sk-xxx ./scripts/run_ab.sh [N]     # 默认 N=3
# 说明:temperature=0 下重复轮次波动很小,N=3 足够看均值±波动;N=10 亦可(更贵更慢)
set -euo pipefail
cd "$(dirname "$0")/.."

N="${1:-3}"
if [ -z "${LLM_API_KEY:-}" ]; then
  echo "缺少 LLM_API_KEY" >&2; exit 1
fi
mkdir -p eval-runs

for i in $(seq 1 "$N"); do
  echo "===== 第 $i/$N 轮: pipeline ====="
  mvn -q test -Dtest=Nl2SqlEvalRunner
  cp eval-report.md "eval-runs/pipeline-run${i}.md"

  echo "===== 第 $i/$N 轮: agent ====="
  BOVIN_CHAT_ENGINE=agent mvn -q test -Dtest=Nl2SqlEvalRunner
  cp eval-report.md "eval-runs/agent-run${i}.md"
done

python3 scripts/ab_summary.py
