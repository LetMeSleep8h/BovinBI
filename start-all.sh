#!/usr/bin/env bash
# BovinBI 一键启动(仓库根执行):backend(含前端页面)+ MySQL + pgvector + python-agent
# 所有 Docker 定义集中在 docker/ 目录(compose + 两个 Dockerfile + Maven 镜像配置)
# 用法: ./start-all.sh            首次构建约几分钟;数据卷保留时再次启动 ~10 秒
#       ./start-all.sh --rebuild  全栈强制重建镜像
#       可先 export LLM_API_KEY=sk-xxx + BOVIN_LLM_PROVIDER=openai 点亮 LLM(双引擎)
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "==> 启动 BovinBI 全栈(backend(含前端) + MySQL + pgvector + python-agent)..."
docker compose -f "$ROOT/docker/docker-compose.yml" up -d --build

echo "==> 等待就绪(健康检查 + 首次装载数据)..."
for i in $(seq 1 90); do
  if curl -sf -m 2 http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ""
    echo "✅ BovinBI 全栈就绪,容器清单:"
    docker compose -f "$ROOT/docker/docker-compose.yml" ps --format "table {{.Name}}\t{{.Service}}\t{{.Status}}" | sed 's/^/   /'
    echo ""
    echo "   ➜ 前端页面:  http://localhost:8080   ← 前端打包在 backend 容器里,随本脚本启动(admin / bovin123)"
    echo "   ➜ 后端 API:  http://localhost:8080/api  |  Swagger: /swagger-ui.html"
    echo "      MCP 端点:  POST /mcp(Bearer bovin-mcp-demo)  |  Python Agent: http://localhost:8090/health"
    echo "   ➜ 本机直连:  MySQL@3308  pgvector@5433"
    echo "   日志排查:  docker logs -f bovinbi-backend"
    exit 0
  fi
  if ! docker ps --format '{{.Names}}' | grep -q bovinbi-backend; then
    echo "❌ 后端容器未运行,最后日志:"
    docker logs --tail 30 bovinbi-backend 2>/dev/null || true
    exit 1
  fi
  sleep 2
done
echo "❌ 180 秒内未就绪,查日志: docker logs --tail 50 bovinbi-backend"
exit 1
