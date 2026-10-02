#!/usr/bin/env bash
# BovinBI 一键停止:后端 + MySQL + pgvector 全部停掉。
# 用法: ./docker/stop-all.sh            停止并保留数据卷(下次启动数据仍在)
#       ./docker/stop-all.sh --purge    停止并清空数据卷(下次启动重新建库装载数据)
set -e
cd "$(dirname "$0")"

if [ "$1" = "--purge" ]; then
  echo "==> 停止 BovinBI 全栈并清空数据卷..."
  docker compose down -v
  echo "✅ 已停止,数据卷已删除(下次 start-all 将重新初始化)"
else
  echo "==> 停止 BovinBI 全栈(数据卷保留)..."
  docker compose down
  echo "✅ 已停止;数据保留在卷 bovinbi-mysql-data / bovinbi-pgvector-data"
  echo "   (如需彻底清空重来: ./docker/stop-all.sh --purge)"
fi
