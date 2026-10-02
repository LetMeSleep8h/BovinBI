package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String role;
    /** AI 执行权限划分:AUTO=完全允许 / STEP=每一步过问(执行前需确认) */
    private String approvalMode;
    private LocalDateTime createdAt;
}
