#!/usr/bin/env bash
# BovinBI 一键启动:MySQL + pgvector + 后端(含前端页面),启动即健康检查通过。
# 用法: ./docker/start-all.sh        (首次会构建镜像并装载数据,约 2~5 分钟)
#       ./docker/start-all.sh --rebuild   强制重建后端镜像
set -e
cd "$(dirname "$0")"

echo "==> 启动 BovinBI 全栈(MySQL 8 + pgvector + 后端)..."
if [ "$1" = "--rebuild" ]; then
  docker compose up -d --build
else
  docker compose up -d --build
fi

echo "==> 等待后端就绪(健康检查 + 首次装载数据)..."
for i in $(seq 1 90); do
  if curl -sf -m 2 http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ""
    echo "✅ BovinBI 全栈就绪:"
    echo "   前端/API:  http://localhost:8080      (admin / bovin123)"
    echo "   MCP 端点:  POST http://localhost:8080/mcp   (bovin.mcp.server-enabled 控制开关)"
    echo "   Swagger:   http://localhost:8080/swagger-ui.html"
    echo "   本机直连:  MySQL localhost:3308 / pgvector localhost:5433"
    echo "   日志排查:  docker logs -f bovinbi-backend"
    exit 0
  fi
  # 后端容器挂了直接给出日志,别傻等
  if ! docker ps --format '{{.Names}}' | grep -q bovinbi-backend; then
    echo "❌ 后端容器未运行,最后日志:"
    docker logs --tail 30 bovinbi-backend 2>/dev/null || true
    exit 1
  fi
  sleep 2
done
echo "❌ 180 秒内未就绪,查日志: docker logs --tail 50 bovinbi-backend"
exit 1
