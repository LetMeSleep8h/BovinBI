package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.dto.ChatReq;
import com.eighthours.bovinbi.dto.MessageVO;
import com.eighthours.bovinbi.dto.SessionVO;
import com.eighthours.bovinbi.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @GetMapping("/sessions")
    public ApiResponse<List<SessionVO>> sessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Long>> create(@RequestBody Map<String, Long> body) {
        Long datasetId = body.get("datasetId");
        return ApiResponse.ok(Map.of("sessionId", chatService.createSession(datasetId)));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatService.deleteSession(id);
        return ApiResponse.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<MessageVO>> messages(@PathVariable Long id) {
        return ApiResponse.ok(chatService.messages(id));
    }

    @PostMapping("/ask")
    public ApiResponse<MessageVO> ask(@Valid @RequestBody ChatReq req) {
        return ApiResponse.ok(chatService.ask(req.sessionId(), req.question()));
    }
}
