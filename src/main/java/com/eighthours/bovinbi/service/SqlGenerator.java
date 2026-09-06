package com.eighthours.bovinbi.service;

/** SQL 生成器抽象:规则引擎(RuleSqlGenerator)与大模型(LlmSqlGenerator)双实现 */
public interface SqlGenerator {

    SqlResult generate(SqlGenContext ctx);
}
