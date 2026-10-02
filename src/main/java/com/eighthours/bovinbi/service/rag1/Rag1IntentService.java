package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.rag.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * rag1 意图识别服务:新问题 → 向量召回 topK 相似问例 → 按意图"最佳单例分"投票。
 * - 投票取每个意图的最高分而非总分:避免某意图语料多而在跨意图相似时占便宜;
 * - 置信度低于阈值不强行分类(UNKNOWN),闲聊兜底交给关键词版 ChitChatHandler ——
 *   rag1 是增强不是替换:识别不了时行为与旧版完全一致;
 * - 相似问例(exemplars)随结果带出,供各引擎作为少样本注入 prompt(见 sideInfo)。
 */
@Slf4j
public class Rag1IntentService {

    private final VectorStore store;
    private final EmbeddingClient embedder;
    private final ChitChatHandler chitChatHandler;
    private final BovinProperties.Rag1 cfg;

    public Rag1IntentService(VectorStore store, EmbeddingClient embedder,
                             ChitChatHandler chitChatHandler, BovinProperties.Rag1 cfg) {
        this.store = store;
        this.embedder = embedder;
        this.chitChatHandler = chitChatHandler;
        this.cfg = cfg;
    }

    /** 识别结果:标签 + 置信度 + 相似问例 + 判定来源(rag1=向量 / keyword=关键词兜底 / none) */
    public record IntentResult(IntentLabel label, double confidence, List<String> exemplars, String source) {

        public static IntentResult unknown() {
            return new IntentResult(IntentLabel.UNKNOWN, 0.0, List.of(), "none");
        }

        public boolean chitChat() {
            return label == IntentLabel.CHIT_CHAT;
        }
    }

    public IntentResult recognize(String question) {
        if (question == null || question.isBlank()) {
            return IntentResult.unknown();
        }
        List<VectorStore.Match> matches = store.search(embedder.embed(question), cfg.getTopK());
        IntentLabel winner = matches.stream()
                .collect(Collectors.toMap(VectorStore.Match::intent, VectorStore.Match::score, Math::max))
                .entrySet().stream()
                .max(Comparator.comparingDouble(e -> e.getValue()))
                .map(e -> e.getKey())
                .orElse(IntentLabel.UNKNOWN);
        double best = matches.stream().filter(m -> m.intent() == winner)
                .mapToDouble(VectorStore.Match::score).max().orElse(0.0);
        List<String> exemplars = matches.stream().limit(2).map(VectorStore.Match::text).toList();

        if (best < cfg.getThreshold()) {
            // 置信度不足:不猜。闲聊关键词兜底仍保留(覆盖 rag1 语料没收录的问候问法)
            return chitChatHandler.isChitChat(question)
                    ? new IntentResult(IntentLabel.CHIT_CHAT, 1.0, List.of(), "keyword")
                    : IntentResult.unknown();
        }
        log.info("rag1 意图: {}({}) 置信度 {} 来源向量库 {} 条", question, winner, String.format("%.2f", best), store.count());
        return new IntentResult(winner, best, exemplars, "rag1");
    }

    /** 注入 prompt 的意图行:标签 + 置信度 + 相似问例(少样本),供生成/编排参考 */
    public String sideInfo(IntentResult r) {
        if (r.label() == IntentLabel.UNKNOWN) {
            return "意图: 未识别(按取数问题处理)";
        }
        String ex = r.exemplars().isEmpty() ? "" : ";相似问例: " + String.join(" | ", r.exemplars());
        return "意图: " + r.label() + String.format("(置信度%.2f", r.confidence()) + ex + ")";
    }

    public long corpusSize() {
        return store.count();
    }
}
