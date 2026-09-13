#!/usr/bin/env python3
"""聚合 eval-runs/ 下的多轮评测报告,输出均值±波动到 eval-runs/AB-SUMMARY.md"""
import re
import statistics
from pathlib import Path

RUNS = Path(__file__).resolve().parent.parent / "eval-runs"

PATTERNS = {
    "准确率%": r"结构准确率\(断言命中\): \*\*([\d.]+)%\*\*",
    "执行成功率%": r"执行成功率\(未降级且SQL可执行\): \*\*([\d.]+)%\*\*",
    "平均耗时ms": r"平均 ([\d.]+) ms/条",
    "平均工具调用": r"工具调用: 平均 ([\d.]+) 次/题",
    "预算触顶题数": r"预算触顶 (\d+) 题",
}


def parse(md: str) -> dict:
    out = {}
    for name, pat in PATTERNS.items():
        m = re.search(pat, md)
        out[name] = float(m.group(1)) if m else None
    return out


def main():
    rows = {}
    for f in sorted(RUNS.glob("*-run*.md")):
        engine = f.name.split("-run")[0]
        rows.setdefault(engine, []).append(parse(f.read_text(encoding="utf-8")))

    lines = ["# A/B 对照评测汇总", ""]
    for engine, runs in rows.items():
        lines.append(f"## {engine}(n={len(runs)})")
        lines.append("")
        lines.append("| 指标 | 均值 | 波动(标准差) | 各轮 |")
        lines.append("|---|---|---|---|")
        for name in PATTERNS:
            vals = [r[name] for r in runs if r[name] is not None]
            if vals:
                mean = statistics.mean(vals)
                std = statistics.stdev(vals) if len(vals) > 1 else 0.0
                each = " / ".join(f"{v:g}" for v in vals)
                lines.append(f"| {name} | {mean:.2f} | {std:.2f} | {each} |")
        lines.append("")
    out = RUNS / "AB-SUMMARY.md"
    out.write_text("\n".join(lines), encoding="utf-8")
    print("\n".join(lines))
    print(f"\n已写入 {out}")


if __name__ == "__main__":
    main()
