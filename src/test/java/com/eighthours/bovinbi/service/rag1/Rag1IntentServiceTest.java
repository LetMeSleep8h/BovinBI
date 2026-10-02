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

/**
 * rag1 意图识别契约(零依赖链路:hash 嵌入 + 进程内向量库 + 真实语料 CSV):
 * 1) 典型问法各归其类:闲聊/趋势/TopN/占比/环比/分组/单指标;
 * 2) 置信度不足不猜:乱码 → UNKNOWN,交给关键词兜底(主链路按取数处理,不误伤);
 * 3) 向量库空时闲聊兜底仍生效:关键词版接管(增强不替换);
 * 4) sideInfo 产出可注入 prompt 的意图行(含相似问例少样本)。
 */
class Rag1IntentServiceTest {

    private Rag1IntentService service;

    @BeforeEach
    void setUp() {
        HashEmbeddingClient embedder = new HashEmbeddingClient();
        InMemoryVectorStore store = new InMemoryVectorStore(embedder);
        store.init();
        store.seedIfEmpty(IntentSeeds.load());
        service = new Rag1IntentService(store, embedder, new ChitChatHandler(), new BovinProperties().getRag1());
    }

    @Test
    void typicalQuestionsClassifyToExpectedIntents() {
        assertEquals(IntentLabel.CHIT_CHAT, service.recognize("你好呀").label());
        assertEquals(IntentLabel.TREND, service.recognize("近12个月每月产奶量趋势").label());
        assertEquals(IntentLabel.TOP_N, service.recognize("产奶量Top10牧场").label());
        assertEquals(IntentLabel.RATIO, service.recognize("上个月各品种产奶量占比").label());
        assertEquals(IntentLabel.COMPARE, service.recognize("产奶量环比怎么样").label());
        assertEquals(IntentLabel.GROUP_STAT, service.recognize("各牧场产奶量").label());
        assertEquals(IntentLabel.METRIC, service.recognize("总产奶量是多少").label());
    }

    @Test
    void paraphraseStillHitsIntent() {
        // 语料未逐字收录的换法:靠向量相似度落到正确意图
        assertEquals(IntentLabel.TOP_N, service.recognize("产奶量前十的牧场有哪些").label());
        assertEquals(IntentLabel.TREND, service.recognize("最近半年产奶量走势").label());
    }

    @Test
    void lowConfidenceFallsBackToUnknownInsteadOfGuessing() {
        Rag1IntentService.IntentResult r = service.recognize("zzzqqq无意义乱码xxx");
        assertEquals(IntentLabel.UNKNOWN, r.label());
        assertEquals("none", r.source());
        assertFalse(r.chitChat());
    }

    @Test
    void chitChatKeywordFallbackWorksOnEmptyCorpus() {
        HashEmbeddingClient embedder = new HashEmbeddingClient();
        InMemoryVectorStore empty = new InMemoryVectorStore(embedder);
        empty.init();
        Rag1IntentService bare = new Rag1IntentService(empty, embedder, new ChitChatHandler(),
                new BovinProperties().getRag1());
        Rag1IntentService.IntentResult r = bare.recognize("你好");
        assertEquals(IntentLabel.CHIT_CHAT, r.label());
        assertEquals("keyword", r.source());
    }

    @Test
    void sideInfoCarriesLabelConfidenceAndExemplars() {
        Rag1IntentService.IntentResult r = service.recognize("近12个月每月产奶量趋势");
        String info = service.sideInfo(r);
        assertTrue(info.startsWith("意图: TREND"), "sideInfo 应带标签,实际: " + info);
        assertTrue(info.contains("相似问例"), "sideInfo 应带相似问例少样本,实际: " + info);
        assertFalse(r.exemplars().isEmpty());
        assertEquals("rag1", r.source());
    }

    @Test
    void seedsCorpusIsWellFormed() {
        List<IntentExample> seeds = IntentSeeds.load();
        assertTrue(seeds.size() >= 40, "语料应覆盖七大意图,实际 " + seeds.size() + " 条");
        assertTrue(seeds.stream().noneMatch(e -> e.text().isBlank()));
        assertTrue(seeds.stream().map(IntentExample::intent).distinct().count() >= 7);
    }
}
