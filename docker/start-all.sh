#!/usr/bin/env bash
# BovinBI 一键启动:MySQL + pgvector + backend(前端页面打包在内)+ python-agent。
# 用法: ./docker/start-all.sh        (首次会构建镜像并装载数据,约 2~5 分钟)
#       ./docker/start-all.sh --rebuild   全栈强制重建镜像
set -e
cd "$(dirname "$0")"

echo "==> 启动 BovinBI 全栈(4 个容器:backend(含前端页面) + MySQL + pgvector + python-agent)..."
docker compose up -d --build

echo "==> 等待就绪(健康检查 + 首次装载数据)..."
for i in $(seq 1 90); do
  if curl -sf -m 2 http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ""
    echo "✅ BovinBI 全栈就绪,容器清单:"
    docker compose ps --format "table {{.Name}}\t{{.Service}}\t{{.Status}}" | sed 's/^/   /'
    echo ""
    echo "   ➜ 前端页面:  http://localhost:8080   ← 就是它!前端打包在 backend 容器里,"
    echo "                 随本脚本一起启动,没有独立的前端容器(admin / bovin123)"
    echo "   ➜ 后端 API:  http://localhost:8080/api  |  Swagger: /swagger-ui.html"
    echo "      MCP 端点:  POST /mcp(Bearer bovin-mcp-demo)"
    echo "   ➜ 本机直连:  MySQL@3308  pgvector@5433  python-agent@8090(/health 可看跨语言链路)"
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
