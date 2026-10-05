# BovinBI Python Agent:AI 能力层(LangChain + LangGraph;经 MCP 回环调用 Java 底座工具)
# 构建上下文 = python-agent/(compose 已配置);手动构建:
#   docker build -f docker/python-agent.Dockerfile -t bovinbi-python-agent python-agent
FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 TZ=Asia/Shanghai
# 国内网络 pip 走清华镜像,避免构建卡死
COPY python-agent/requirements.txt .
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
COPY python-agent/app ./app
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
