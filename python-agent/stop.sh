#!/usr/bin/env bash
# =============================================================================
# Python Agent 停止(start.sh 配套):优先按 agent.pid 精准停;
# PID 文件丢失时按端口找 uvicorn 进程 —— 只认命令行带 uvicorn+app.main 的,
# 不会误杀 Docker 的端口转发(com.docker.backend),全栈容器不受影响
# 同时暂停 Neo4j 容器(start.sh 拉起的那个;数据保留在卷,彻底删除: docker rm -v neo4j)
# 用法:
#   ./stop.sh             默认端口 8090(与 start.sh 的 PORT 保持一致)
#   PORT=8091 ./stop.sh
# =============================================================================
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$DIR/agent.pid"
PORT="${PORT:-8090}"

PID=""
if [ -f "$PID_FILE" ]; then
  PID="$(cat "$PID_FILE")"
elif command -v lsof >/dev/null 2>&1; then
  for p in $(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null); do
    if ps -p "$p" -o command= 2>/dev/null | grep -q "uvicorn.*app.main"; then
      PID="$p"
    fi
  done
fi

if [ -z "$PID" ] || ! kill -0 "$PID" 2>/dev/null; then
  echo "ℹ️  未发现运行中的 Python Agent"
  rm -f "$PID_FILE"
  PID=""
fi

if [ -n "$PID" ]; then
  echo "==> 停止 Python Agent (PID $PID)..."
  kill "$PID" 2>/dev/null || true
  for i in $(seq 1 10); do
    kill -0 "$PID" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$PID" 2>/dev/null; then
    echo "==> 10 秒未退出,强制 kill -9"
    kill -9 "$PID" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "✅ Python Agent 已停止"
fi

# ---- Neo4j 容器一并暂停(只在它运行时;Agent 不在也不妨碍单独停库) ----
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
   && docker ps --format '{{.Names}}' | grep -qx neo4j; then
  echo "==> 暂停 Neo4j 容器..."
  docker stop neo4j >/dev/null
  echo "✅ Neo4j 已停止"
fi
