package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("query_log")
public class QueryLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private Long datasetId;
    private String question;
    private String finalSql;
    /** RULE / LLM / CACHE */
    private String engine;
    /** SUCCESS / FAILED / REFUSED */
    private String status;
    private Integer rowCount;
    private Integer costMs;
    private Integer cacheHit;
    private String errorMsg;
    private LocalDateTime createdAt;
}
