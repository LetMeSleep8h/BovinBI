package com.eighthours.bovinbi.evaluation;

import com.eighthours.bovinbi.service.ChitChatHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 意图分流单测:闲聊拦截 + 带问候的真实取数不误伤 */
class ChitChatHandlerTest {

    private final ChitChatHandler handler = new ChitChatHandler();

    @Test
    void metaQuestionsAreChitChat() {
        assertTrue(handler.isChitChat("你能做什么"));
        assertTrue(handler.isChitChat("你能干什么"));
        assertTrue(handler.isChitChat("你是谁"));
        assertTrue(handler.isChitChat("你好"));
        assertTrue(handler.isChitChat("Hello"));
        assertTrue(handler.isChitChat("谢谢"));
    }

    @Test
    void dataQuestionsWithGreetingNotMisrouted() {
        assertFalse(handler.isChitChat("你好,上个月各品种产奶量占比"), "带问候的取数不能分流");
        assertFalse(handler.isChitChat("你好呀,近3个月每月各牧场产奶量"));
        assertFalse(handler.isChitChat("hi,今年总产奶量"));
        assertFalse(handler.isChitChat("产奶量Top10牧场"));
        assertFalse(handler.isChitChat("2026年3月泌乳牛数"));
    }

    @Test
    void longQuestionsNeverChitChat() {
        assertFalse(handler.isChitChat("你好".repeat(30)));
    }
}
