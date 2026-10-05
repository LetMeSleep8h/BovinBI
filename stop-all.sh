#!/usr/bin/env bash
# BovinBI 一键停止(仓库根执行):backend + MySQL + pgvector + python-agent 全部停掉
# 用法: ./stop-all.sh            停止并保留数据卷(下次启动数据仍在)
#       ./stop-all.sh --purge    停止并清空数据卷(下次启动重新建库装载数据)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

if [ "$1" = "--purge" ]; then
  echo "==> 停止 BovinBI 全栈并清空数据卷..."
  docker compose -f "$ROOT/docker/docker-compose.yml" down -v
  echo "✅ 已停止,数据卷已删除(下次 start-all 将重新初始化)"
else
  echo "==> 停止 BovinBI 全栈(数据卷保留)..."
  docker compose -f "$ROOT/docker/docker-compose.yml" down
  echo "✅ 已停止;数据保留在卷 bovinbi-mysql-data / bovinbi-pgvector-data"
  echo "   (如需彻底清空重来: ./stop-all.sh --purge)"
fi
