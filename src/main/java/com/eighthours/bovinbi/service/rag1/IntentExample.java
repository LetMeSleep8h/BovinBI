package com.eighthours.bovinbi.service.rag1;

/**
 * 标注问例(rag1 的"训练数据"):一条问题文本 + 它的意图标签。
 * 语料是 CSV 明细(resources/rag1/intent-examples.csv),加标注例不改代码只改数据。
 */
public record IntentExample(IntentLabel intent, String text) {

    /** 稳定主键:意图+文本哈希,幂等 upsert 依赖它(重启不重复灌库) */
    public String id() {
        return Integer.toHexString((intent().name() + '|' + text()).hashCode());
    }
}
