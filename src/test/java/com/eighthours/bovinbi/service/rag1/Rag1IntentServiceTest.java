package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.rag.HashEmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * rag1 意图识别纯逻辑单测(向量库 mock;pgvector 真实链路见 PgVectorStoreIntegrationTest):
 * 1) 投票:每意图取最佳单例分,跨意图相似时语料多的意图不占便宜;
 * 2) 置信度不足不猜:UNKNOWN + 闲聊关键词兜底(增强不替换);
 * 3) sideInfo 产出可注入 prompt 的意图行(含相似问例少样本);
 * 4) 语料 CSV 结构完好。
 */
class Rag1IntentServiceTest {

    private final BovinProperties.Rag1 cfg = new BovinProperties.Rag1();
    private final VectorStore store = mock(VectorStore.class);
    private Rag1IntentService service;

    @BeforeEach
    void setUp() {
        cfg.setTopK(3);
        service = new Rag1IntentService(store, new HashEmbeddingClient(), new ChitChatHandler(), cfg);
    }

    private void stubMatches(VectorStore.Match... matches) {
        when(store.search(any(), anyInt())).thenReturn(List.of(matches));
    }

    @Test
    void bestMatchPerIntentWinsVote() {
        // TREND 两条 0.4/0.5(总分高)但 TOP_N 单条 0.9 → 按最佳单例分 TOP_N 胜出
        stubMatches(new VectorStore.Match(IntentLabel.TOP_N, "产奶量Top10牧场", 0.90),
                new VectorStore.Match(IntentLabel.TREND, "每月产奶量趋势", 0.50),
                new VectorStore.Match(IntentLabel.TREND, "每日产奶量走势", 0.40));
        Rag1IntentService.IntentResult r = service.recognize("产奶量前十的牧场");
        assertEquals(IntentLabel.TOP_N, r.label());
        assertEquals(0.90, r.confidence());
        assertEquals("rag1", r.source());
        assertFalse(r.exemplars().isEmpty());
    }

    @Test
    void belowThresholdFallsBackToUnknownOrKeywordChitchat() {
        stubMatches(new VectorStore.Match(IntentLabel.METRIC, "总产奶量是多少", 0.30));
        assertEquals(IntentLabel.UNKNOWN, service.recognize("zzzqqq乱码").label());

        // 置信度不足但命中闲聊特征词 → 关键词兜底判闲聊
        Rag1IntentService.IntentResult r = service.recognize("你好");
        assertEquals(IntentLabel.CHIT_CHAT, r.label());
        assertEquals("keyword", r.source());
    }

    @Test
    void emptyMatchesReturnUnknown() {
        when(store.search(any(), anyInt())).thenReturn(List.of());
        assertEquals(IntentLabel.UNKNOWN, service.recognize("任意问题").label());
    }

    @Test
    void sideInfoCarriesLabelConfidenceAndExemplars() {
        stubMatches(new VectorStore.Match(IntentLabel.TREND, "每月产奶量趋势", 0.9));
        Rag1IntentService.IntentResult r = service.recognize("近12个月每月产奶量趋势");
        String info = service.sideInfo(r);
        assertTrue(info.startsWith("意图: TREND"), "sideInfo 应带标签,实际: " + info);
        assertTrue(info.contains("相似问例"), "sideInfo 应带相似问例少样本,实际: " + info);
        assertTrue(Rag1IntentService.format(null).contains("未识别"), "空结果格式化应提示未识别");
    }

    @Test
    void seedsCorpusIsWellFormed() {
        List<IntentExample> seeds = IntentSeeds.load();
        assertTrue(seeds.size() >= 40, "语料应覆盖七大意图,实际 " + seeds.size() + " 条");
        assertTrue(seeds.stream().noneMatch(e -> e.text().isBlank()));
        assertTrue(seeds.stream().map(IntentExample::intent).distinct().count() >= 7);
    }
}
