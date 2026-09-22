package com.eighthours.bovinbi.service.rag;

import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 装配:EmbeddingClient 按 bovin.rag.embedding.provider 选择实现(hash=零依赖默认 / openai=生产)。
 */
@Configuration
public class RagConfig {

    @Bean
    public EmbeddingClient embeddingClient(BovinProperties props, ObjectMapper objectMapper) {
        BovinProperties.Rag.Embedding e = props.getRag().getEmbedding();
        if ("openai".equalsIgnoreCase(e.getProvider())) {
            return new OpenAiEmbeddingClient(e, objectMapper);
        }
        return new HashEmbeddingClient();
    }
}
