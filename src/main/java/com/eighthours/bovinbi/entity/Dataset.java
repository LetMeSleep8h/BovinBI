package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dataset")
public class Dataset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    /** 该数据集可查询的物理表白名单,逗号分隔 */
    private String dwhTables;
    private String status;
    private LocalDateTime createdAt;
}
