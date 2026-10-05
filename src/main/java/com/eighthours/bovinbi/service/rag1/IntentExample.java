package com.eighthours.bovinbi.service.rag1;

/**
 * 标注问例(rag1 的"训练数据"):一条问题文本 + 它的意图标签。
 * 语料是 CSV 明细(resources/rag1/intent-examples.csv),加标注例不改代码只改数据。
 */
public record IntentExample(IntentLabel intent, String text) {

    /** 稳定主键:意图+文本的 SHA-256 摘要,幂等 upsert 依赖它(重启不重复灌库);
     *  早期版本用 hashCode 十六进制截断,新增语料时会发生碰撞覆盖(丢行) */
    public String id() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((intent().name() + '|' + text()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
