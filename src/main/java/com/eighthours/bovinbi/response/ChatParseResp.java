package com.eighthours.bovinbi.response;

import com.eighthours.bovinbi.dto.ParseState;
import com.eighthours.bovinbi.dto.SemanticParseInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatParseResp {
    private Long queryId;
    private ParseState state;
    @Builder.Default
    private List<SemanticParseInfo> candidates = new ArrayList<>();
    private String errorMsg;
    private Long costMs;
}
