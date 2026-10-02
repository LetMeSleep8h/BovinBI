package com.eighthours.bovinbi.service.rag1;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 标注问例语料加载:resources/rag1/intent-examples.csv(intent,text 两列)。
 * 扩展意图识别能力 = 往 CSV 加行 + 重启(seedIfEmpty 仅空库灌入;已建库需清表或换表名)。
 */
@Slf4j
public final class IntentSeeds {

    private IntentSeeds() {
    }

    public static List<IntentExample> load() {
        List<IntentExample> out = new ArrayList<>();
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader("intent", "text")
                .setSkipHeaderRecord(true)
                .build()
                .parse(new InputStreamReader(
                        new ClassPathResource("rag1/intent-examples.csv").getInputStream(),
                        StandardCharsets.UTF_8))) {
            parser.forEach(r -> out.add(new IntentExample(IntentLabel.valueOf(r.get(0).trim()), r.get(1).trim())));
        } catch (Exception e) {
            log.warn("意图问例语料加载失败,rag1 将以空库运行: {}", e.getMessage());
        }
        return out;
    }
}
