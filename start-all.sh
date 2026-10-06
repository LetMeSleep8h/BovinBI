#!/usr/bin/env bash
# =============================================================================
# BovinBI 一键启动(仓库根执行)
# 起 4 个容器:backend(含前端页面) + MySQL 8 + pgvector + python-agent(LangGraph)
# 所有 Docker 定义集中在 docker/ 目录;LLM Key 配置在 docker/.env(不进 git)
#
# 用法:
#   ./start-all.sh            首次构建约几分钟;数据卷保留时再次启动 ~10 秒
#   ./start-all.sh --rebuild  全栈强制重建镜像
#
# LLM 模式:
#   docker/.env 里配了 LLM_API_KEY → 双引擎 LLM 全开(自动识别,无需 export)
#   未配置                     → 离线模式(规则引擎 + 闲聊固定文案,零依赖可演示)
#   模板: cp docker/env.example docker/.env 后编辑
# =============================================================================
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
COMPOSE="docker compose -f $ROOT/docker/docker-compose.yml"

# ---- LLM 模式识别(docker/.env 为 compose 变量源) ----
if [ -f "$ROOT/docker/.env" ] && grep -q "^LLM_API_KEY=sk-" "$ROOT/docker/.env"; then
  MODE="🧠 LLM 模式(双引擎 AI)"
else
  MODE="🔌 离线模式(未配置 docker/.env,规则引擎+固定文案)"
fi

echo "==> 启动 BovinBI 全栈 [$MODE]"
echo "    backend(含前端) + MySQL + pgvector + python-agent"
$COMPOSE up -d --build

echo "==> 等待就绪(健康检查 + 首次装载数据)..."
for i in $(seq 1 90); do
  if curl -sf -m 2 http://localhost:8080/api/health >/dev/null 2>&1; then
    echo ""
    echo "✅ BovinBI 全栈就绪 [$MODE]"
    echo ""
    echo "   容器清单:"
    $COMPOSE ps --format "table {{.Name}}\t{{.Service}}\t{{.Status}}" | sed 's/^/     /'
    echo ""
    echo "   ➜ 前端页面:  http://localhost:8080   (admin / bovin123)"
    echo "      提问可选 ☕Java 引擎 / 🐍Python Agent;闲聊直接聊(三角色 AI)"
    echo "   ➜ 状态检查:"
    printf "      Java 引擎:  "
    curl -sf -m 3 http://localhost:8080/api/health | grep -o '"llmProvider":"[^"]*"' | cut -d'"' -f4 || echo "未知"
    printf "      Python AI:  "
    PY="启动中..."
    for j in 1 2 3 4 5 6; do
      R=$(curl -sf -m 2 http://localhost:8090/health 2>/dev/null | grep -o '"llm":"[^"]*"' | cut -d'"' -f4) && PY="$R" && break
      sleep 2
    done
    echo "$PY"
    echo "   ➜ 其它入口:  Swagger /swagger-ui.html | MCP POST /mcp(Bearer bovin-mcp-demo)"
    echo "      本机直连:  MySQL@3308  pgvector@5433"
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
