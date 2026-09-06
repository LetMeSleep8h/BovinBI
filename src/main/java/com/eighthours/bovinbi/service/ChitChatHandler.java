package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.dto.AnswerPayload;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 意图前置分流(管线第 0 步):把"问候/询问能力/道谢"这类非取数输入拦在 NL2SQL 之前,
 * 避免大模型把闲聊硬编成 SQL 而报"查询失败"。
 * 只匹配短句且命中特征词,防止把正文里带"你好"的正常取数问题误伤。
 */
@Component
public class ChitChatHandler {

    private static final Pattern META = Pattern.compile(
            "(你能|你会)(做|干)(什么|啥)|(你|您)(是|叫)(谁|什么)|你好|您好|哈喽|hello|hi|嗨|在吗|谢谢|多谢|感谢|再见|拜拜");

    /** 数据信号词:只要命中,说明是取数问题(即使带问候语),不判为闲聊 */
    private static final Pattern DATA_SIGNAL = Pattern.compile(
            "产奶量|奶量|乳脂率|乳蛋白|单产|泌乳|挤奶|牛只|牛数|头数|牧场|品种|牛舍|胎次"
                    + "|趋势|top|排行|前\\d|占比|份额|环比|同比"
                    + "|地区|规模|季度|每月|按月|每天|每日|按天|近\\d|多少|几个|几种|最多|最少|最高|最低|排名|\\d{4}年");

    public boolean isChitChat(String question) {
        if (question == null || question.length() > 50) {
            return false;
        }
        String lower = question.toLowerCase();
        return META.matcher(lower).find() && !DATA_SIGNAL.matcher(lower).find();
    }

    public AnswerPayload answer(String question) {
        AnswerPayload p = new AnswerPayload();
        p.setEngine("RULE");
        p.setTookMs(1);
        p.setCacheHit(false);
        String q = question == null ? "" : question;
        if (q.contains("谢谢") || q.contains("多谢") || q.contains("感谢")) {
            p.setExplanation("不客气!还想看什么数据,直接问就行。");
            return p;
        }
        if (q.contains("再见") || q.contains("拜拜")) {
            p.setExplanation("再见!数据随时在这里等你。");
            return p;
        }
        p.setExplanation("我是 BovinBI 数据分析助手,专门回答「牧场养殖分析」数据集的问题:"
                + "产奶量/平均单产/乳脂率/乳蛋白率/泌乳牛数等指标,支持趋势、TopN 排行、占比、环比同比、维度分组。"
                + "试试:近12个月每月产奶量趋势、产奶量Top10牧场、上个月各品种产奶量占比、近3个月每月各牧场产奶量。"
                + "注意:数据里没有的信息(如员工工资、牛只交易价格)我无法回答。");
        return p;
    }
}
