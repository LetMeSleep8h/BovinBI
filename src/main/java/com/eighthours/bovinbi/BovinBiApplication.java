package com.eighthours.bovinbi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BovinBiApplication {

    static {
        // LLM 端点多 A 记录可能含不可达 IP(如 DeepSeek 103.220.64.100);
        // 缩短 JVM DNS 缓存,让应用层重试(LangChain4jClient.generateWithDnsRetry)
        // 能重新解析并换到可达 IP
        java.security.Security.setProperty("networkaddress.cache.ttl", "5");
        java.security.Security.setProperty("networkaddress.cache.negative.ttl", "2");
    }


    public static void main(String[] args) {
        SpringApplication.run(BovinBiApplication.class, args);
    }

}
