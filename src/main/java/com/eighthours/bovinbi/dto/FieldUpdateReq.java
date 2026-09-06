package com.eighthours.bovinbi.dto;

/** 字段口径维护请求 */
public record FieldUpdateReq(String alias, String synonyms, String description, String aggType) {
}
