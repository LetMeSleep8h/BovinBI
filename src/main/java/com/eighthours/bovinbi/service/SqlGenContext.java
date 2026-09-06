package com.eighthours.bovinbi.service;

import java.util.List;

/** SQL 生成上下文 */
public record SqlGenContext(String question, TimeRange timeRange, SchemaLinker.LinkResult schema) {

    public List<com.eighthours.bovinbi.entity.DatasetField> fields() {
        return schema.matched();
    }
}
