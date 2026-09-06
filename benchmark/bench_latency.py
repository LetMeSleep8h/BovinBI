#!/usr/bin/env python3
"""
BovinBI 延迟基准脚本(P50/P95/P99 + 缓存命中率)
用法: 先启动后端并登录,然后 python3 bench_latency.py
用于产出简历量化数据(响应延迟、缓存收益)。仅依赖 Python 标准库。
"""
import argparse
import json
import random
import statistics
import time
import urllib.request

QUESTIONS = [
    "近12个月每月产奶量趋势", "产奶量Top10牧场", "上个月各品种产奶量占比", "今年总产奶量",
    "近3个月每月泌乳牛数趋势", "今年平均单产", "乳脂率是多少", "近30天每天产奶量趋势",
    "各地区泌乳牛数", "大型牧场的产奶量", "今年Q2各季度乳脂率", "去年每月产奶量趋势",
]

def call(base, path, payload=None, token=None):
    req = urllib.request.Request(base + path, method="POST" if payload is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(payload).encode() if payload is not None else None
    with urllib.request.urlopen(req, data=data, timeout=60) as resp:
        return json.loads(resp.read())

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="bovin123")
    ap.add_argument("--rounds", type=int, default=30, help="每个问题先冷启动1次,再压测N次")
    args = ap.parse_args()

    r = call(args.base, "/api/auth/login", {"username": args.user, "password": args.password})
    token = r["data"]["token"]
    ds = call(args.base, "/api/datasets", token=token)["data"][0]
    sid = call(args.base, "/api/chat/sessions", {"datasetId": ds["id"]}, token)["data"]["sessionId"]

    cold, warm, cache_hit = [], [], 0
    total = 0
    for q in QUESTIONS:
        # 冷启动(填缓存)
        t0 = time.perf_counter()
        call(args.base, "/api/chat/ask", {"sessionId": sid, "question": q}, token)
        cold.append((time.perf_counter() - t0) * 1000)

        for _ in range(args.rounds):
            t1 = time.perf_counter()
            resp = call(args.base, "/api/chat/ask", {"sessionId": sid, "question": q}, token)
            dt = (time.perf_counter() - t1) * 1000
            warm.append(dt)
            total += 1
            if resp["data"]["payload"].get("cacheHit"):
                cache_hit += 1

    def pct(xs, p):
        xs = sorted(xs)
        return xs[int(len(xs) * p) - 1]

    print(f"样本: {len(QUESTIONS)} 个问题 x (1冷 + {args.rounds}热)")
    print(f"冷启动(含SQL生成+执行): P50={pct(cold,0.5):.0f}ms  P95={pct(cold,0.95):.0f}ms  均值={statistics.mean(cold):.0f}ms")
    print(f"热查询(命中缓存):       P50={pct(warm,0.5):.0f}ms  P95={pct(warm,0.95):.0f}ms  均值={statistics.mean(warm):.0f}ms")
    print(f"缓存命中率: {cache_hit}/{total} = {cache_hit*100.0/total:.1f}%")
    print(f"加速比(冷P50/热P50): {pct(cold,0.5)/pct(warm,0.5):.1f}x")

if __name__ == "__main__":
    main()
