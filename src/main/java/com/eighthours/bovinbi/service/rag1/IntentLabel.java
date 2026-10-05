package com.eighthours.bovinbi.service.rag1;

/**
 * 意图标签体系(与规则引擎的 SQL 模板一一对应):
 * 意图识别的价值不在"多分类几个标签",而在把分类结果喂回链路 ——
 * 闲聊拦截(省一次 LLM)、少样本注入(生成更稳)、引擎路由(编排分发)。
 */
public enum IntentLabel {

    /** 非取数输入:问候/询问能力/道谢,拦在 NL2SQL 之前 */
    CHIT_CHAT,
    /** 趋势:按时间粒度看指标变化(月/日) */
    TREND,
    /** TopN 排行:最高/最低/前 N */
    TOP_N,
    /** 占比/构成:按维度看份额分布 */
    RATIO,
    /** 对比:环比/同比/两期比较 */
    COMPARE,
    /** 分组统计:各 X 的指标(无时间轴、无份额语义) */
    GROUP_STAT,
    /** 单指标:总量/均值/计数 */
    METRIC,
    /** 召回置信度不足,不强行分类(主链路按取数问题处理) */
    UNKNOWN,
    /** LLM 判定为取数(置信度取向量召回相似度,仅作参考) */
    QUERY_LIKE
}
