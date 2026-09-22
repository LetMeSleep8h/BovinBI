package com.eighthours.bovinbi.service.rag;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.service.SchemaLinker;
import com.eighthours.bovinbi.service.SchemaRetriever;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 混合 Schema 召回(RAG 的管线落点,bovin.rag.enabled=true 时以 @Primary 接管 SchemaRetriever):
 * 归一化词面分 0.4 + 字段文档与问题的向量余弦 0.6 → 重排 → topK 截断。
 * - 截断解决"几十上百张表全字段进 prompt"的 token 膨胀与注意力稀释;
 * - 白名单永远全量(守护不受截断影响,截断只影响喂给模型的文本);
 * - 字段数 <= topK 时直接透传词面结果(当前演示数据集即此形态,行为零变化);
 * - 词面分来自 {@link SchemaLinker} 的打分(保序),向量分提供跨词面的语义召回。
 */
@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "bovin.rag.enabled", havingValue = "true")
public class HybridSchemaRetriever implements SchemaRetriever {

    /** 混合分里向量相似度的权重(其余归词面分) */
    private static final double VECTOR_WEIGHT = 0.6;
    /** 词面分归一化上限(别名3+列名2+同义词2) */
    private static final double MAX_LEXICAL = 7.0;

    private final SchemaLinker lexical;
    private final EmbeddingClient embedder;
    private final BovinProperties props;

    public HybridSchemaRetriever(SchemaLinker lexical, EmbeddingClient embedder, BovinProperties props) {
        this.lexical = lexical;
        this.embedder = embedder;
        this.props = props;
    }

    @Override
    public LinkedSchema retrieve(Long datasetId, String question) {
        SchemaLinker.OrderedLink full = lexical.linkOrdered(datasetId, question);
        int topK = props.getRag().getTopK();
        if (full.scored().size() <= topK) {
            return full.base(); // 小域不截断,行为与词面版一致
        }

        final float[] qv = embedder.embed(question == null ? "" : question);
        record Scored(DatasetField f, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (SchemaLinker.ScoredField sf : full.scored()) {
            double cos = EmbeddingClient.cosine(embedder.embed(fieldDoc(sf.f())), qv);
            double lex = Math.min(1.0, sf.score() / MAX_LEXICAL);
            scored.add(new Scored(sf.f(), VECTOR_WEIGHT * cos + (1 - VECTOR_WEIGHT) * lex));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<DatasetField> kept = scored.stream().limit(topK).map(Scored::f).toList();
        log.info("RAG 混合召回: {} 个字段重排后截断为 top{}", full.scored().size(), topK);
        return new LinkedSchema(SchemaLinker.buildText(full.ds(), kept), full.whitelist());
    }

    /** 字段的向量化文档:业务名 + 同义词 + 口径 + 列名(语义召回的主要信号面) */
    private String fieldDoc(DatasetField f) {
        return String.join(" ", Stream.of(
                        f.getAlias(), f.getSynonyms(), f.getDescription(), f.getColumnName())
                .filter(s -> s != null && !s.isBlank()).toList());
    }
}
