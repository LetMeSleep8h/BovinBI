package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dataset_field")
public class DatasetField {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long datasetId;
    private String tableName;
    private String columnName;
    /** 业务别名,如 "销售额" */
    private String alias;
    /** DIMENSION / METRIC */
    private String fieldType;
    private String dataType;
    /** SUM / COUNT_DISTINCT / COUNT / AVG / MAX / MIN / NONE */
    private String aggType;
    /** 同义词,逗号分隔 */
    private String synonyms;
    /** 口径描述,进入 NL2SQL 提示词 */
    private String description;
    private Integer isHidden;
    private LocalDateTime createdAt;
}
