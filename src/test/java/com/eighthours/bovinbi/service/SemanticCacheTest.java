package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 语义缓存单测:归一化 key 与"只缓存成功结果"策略 */
class SemanticCacheTest {

    private final SemanticCache cache = new SemanticCache(new BovinProperties());

    private AnswerPayload successPayload() {
        AnswerPayload p = new AnswerPayload();
        p.setSql("SELECT 1");
        p.setRowCount(1);
        return p;
    }

    @Test
    void normalizeIgnoresCasePunctAndSpaces() {
        assertEquals(SemanticCache.normalize("总产奶量?"), SemanticCache.normalize("总 产奶量！"));
        assertEquals(SemanticCache.normalize("Top10 牧场"), SemanticCache.normalize("top10牧场"));
    }

    @Test
    void keyDistinguishesDatasetAndTime() {
        String noTime = cache.key(1L, "总产奶量", null);
        assertEquals(noTime, cache.key(1L, "总产奶量?", null));
        assertNotEquals(noTime, cache.key(2L, "总产奶量", null));
        assertNotEquals(noTime, cache.key(1L, "总产奶量",
                new TimeRange(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2027, 1, 1), "2026年")));
    }

    @Test
    void onlySuccessfulResultsAreCached() {
        String key = cache.key(1L, "总产奶量", null);

        AnswerPayload fallback = new AnswerPayload();
        fallback.setFallback(true);
        cache.put(key, fallback);
        assertNull(cache.get(key), "降级结果不该进缓存");

        cache.put(key, successPayload());
        assertNotNull(cache.get(key));
    }
}
