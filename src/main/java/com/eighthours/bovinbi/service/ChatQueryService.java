package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.dto.AnswerPayload;

import com.eighthours.bovinbi.request.ChatExecuteReq;
import com.eighthours.bovinbi.request.ChatParseReq;
import com.eighthours.bovinbi.response.ChatParseResp;

/**
 * 问答两段式接口(参照 SuperSonic 的 parse/execute 分离):
 * parse 只负责理解问题、生成并校验 SQL(不查库);
 * execute 按问题 ID + 候选序号取回已校验的 SQL 查库出数据。
 */
public interface ChatQueryService {

    /** 理解问题:产出候选解析(含守护后的 SQL),不执行 */
    ChatParseResp parse(ChatParseReq req);

    /** 执行查询:按 queryId + parseId 取回解析结果并查库 */
    AnswerPayload execute(ChatExecuteReq req);
}
