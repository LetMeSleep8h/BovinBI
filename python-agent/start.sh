#!/usr/bin/env bash
# =============================================================================
# Python Agent 单独启动(python-agent/ 目录下执行,不依赖 Docker 全栈)
# - 首次运行自动建 .venv 并装依赖;requirements.txt 未变化时跳过安装,重启秒级
# - LLM Key 优先取当前环境变量;未设置且存在 docker/.env 时自动读取(双引擎共用一把 Key)
# - 取数工具经 MCP 回环 Java 底座:完整链路需先在仓库根 ./start-all.sh
#
# 用法:
#   ./start.sh                    默认端口 8090
#   PORT=8091 ./start.sh          8090 被占用时换端口
#   LLM_API_KEY=sk-xxx ./start.sh 显式给 Key
# =============================================================================
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

PORT="${PORT:-8090}"
VENV="$DIR/.venv"
PY="$VENV/bin/python"
PID_FILE="$DIR/agent.pid"
LOG="$DIR/logs/agent.log"
STAMP="$VENV/.deps-installed"

# ---- 已在运行则直接退出 ----
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "✅ Python Agent 已在运行 (PID $(cat "$PID_FILE")): curl http://localhost:$PORT/health"
  exit 0
fi

# ---- 端口占用检查(避免和 compose 的 python-agent 撞车) ----
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "❌ 端口 $PORT 已被占用:"
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN | sed 's/^/     /'
  echo "   若是 Docker 全栈的容器: docker stop bovinbi-python-agent"
  echo "   或换个端口:             PORT=8091 ./start.sh"
  exit 1
fi

# ---- LLM 模式识别:环境变量优先,缺省回落 docker/.env(只捞 LLM_ 三件套) ----
if [ -z "${LLM_API_KEY:-}" ] && [ -f "$DIR/../docker/.env" ]; then
  while IFS= read -r line; do
    case "$line" in
      LLM_API_KEY=*|LLM_BASE_URL=*|LLM_MODEL=*) export "$line" ;;
    esac
  done < "$DIR/../docker/.env"
fi
if [ -n "${LLM_API_KEY:-}" ]; then
  MODE="🧠 LLM 模式(${LLM_MODEL:-deepseek-chat})"
else
  MODE="🔌 离线模式(仅闲聊直答;配 LLM_API_KEY 后取数可用)"
fi

# ---- 选择解释器:依赖栈(mcp/langchain-mcp-adapters)硬要求 Python ≥3.10,
#      与 Dockerfile 的 python:3.12 对齐;PYTHON_BIN 可显式指定 ----
PY_BIN="${PYTHON_BIN:-}"
if [ -z "$PY_BIN" ]; then
  for c in python3.12 python3.13 python3.11 python3.10 python3; do
    if command -v "$c" >/dev/null 2>&1 \
       && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
      PY_BIN="$c"
      break
    fi
  done
fi
if [ -z "$PY_BIN" ]; then
  echo "❌ 未找到 Python ≥3.10(本机 python3 可能是 3.9,依赖栈装不上)"
  echo "   安装: brew install python@3.12   或指定: PYTHON_BIN=/路径/python3.12 ./start.sh"
  exit 1
fi

# ---- venv + 依赖(带时间戳,依赖未变不重装;venv 版本不符自动重建) ----
if [ -x "$PY" ] && ! "$PY" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' 2>/dev/null; then
  echo "==> .venv 的 Python 版本过低,重建虚拟环境(改用 $PY_BIN)"
  rm -rf "$VENV"
fi
if [ ! -x "$PY" ]; then
  echo "==> 首次运行:用 $PY_BIN 创建虚拟环境 .venv"
  "$PY_BIN" -m venv "$VENV"
fi
if [ ! -f "$STAMP" ] || [ "$DIR/requirements.txt" -nt "$STAMP" ]; then
  echo "==> 安装依赖(requirements.txt)"
  # 与 Dockerfile 一致走清华源;如需官方源删掉 -i 参数即可
  "$PY" -m pip install -q --disable-pip-version-check \
    -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
  touch "$STAMP"
fi

# ---- 后台启动 ----
mkdir -p "$DIR/logs"
echo "==> 启动 Python Agent [$MODE] 端口 $PORT(日志 logs/agent.log)"
printf '\n===== %s start.sh 启动 =====\n' "$(date '+%F %T')" >> "$LOG"
PYTHONUNBUFFERED=1 nohup "$VENV/bin/uvicorn" app.main:app --host 0.0.0.0 --port "$PORT" \
  >> "$LOG" 2>&1 &
echo $! > "$PID_FILE"

# ---- 就绪等待(进程挂了直接给日志,不傻等) ----
for i in $(seq 1 30); do
  if curl -sf -m 2 "http://localhost:$PORT/health" >/dev/null 2>&1; then
    echo ""
    echo "✅ Python Agent 就绪 [$MODE]"
    echo "   ➜ 探活:  curl http://localhost:$PORT/health"
    echo "   ➜ 提问:  curl -X POST http://localhost:$PORT/v1/answer \\"
    echo "             -H 'Content-Type: application/json' -d '{\"datasetId\":1,\"question\":\"你好\"}'"
    echo "   ➜ 完整取数链路需 Java 底座: 仓库根 ./start-all.sh(默认已对接 localhost:8080/mcp)"
    echo "   ➜ 停止:  ./stop.sh    实时日志:  tail -f logs/agent.log"
    exit 0
  fi
  if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "❌ 进程已退出,最后日志:"
    tail -n 30 "$LOG" | sed 's/^/     /'
    rm -f "$PID_FILE"
    exit 1
  fi
  sleep 1
done
echo "❌ 30 秒内未就绪,查日志: tail -n 50 logs/agent.log"
exit 1
