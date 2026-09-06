package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** USER / ASSISTANT */
    private String role;
    private String content;
    /** 结构化结果 JSON(SQL/列/行/图表/耗时/缓存命中) */
    private String payload;
    private LocalDateTime createdAt;
}
