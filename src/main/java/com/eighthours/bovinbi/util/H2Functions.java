package com.eighthours.bovinbi.util;

import java.time.LocalDate;
import java.time.temporal.WeekFields;

/**
 * H2(MySQL 兼容模式)缺少 MySQL 方言函数,启动时以 CREATE ALIAS 注册本类,使生成的 SQL
 * 在 H2 演示库与 MySQL 生产库间零改动互通。
 */
public class H2Functions {

    /** 实现 MySQL DATE_FORMAT 常用格式符:%Y %m %d %H %i %s 以及 %x-W%v(ISO 周) */
    public static String dateFormat(LocalDate date, String format) {
        if (date == null) return null;
        String f = format == null ? "%Y-%m-%d" : format;
        if (f.contains("%x-W%v")) {
            int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
            int year = date.get(WeekFields.ISO.weekBasedYear());
            return String.format("%04d-W%02d", year, week);
        }
        String pattern = f.replace("%Y", "uuuu").replace("%m", "MM").replace("%d", "dd")
                .replace("%H", "HH").replace("%i", "mm").replace("%s", "ss").replace("%j", "DDD");
        return java.time.format.DateTimeFormatter.ofPattern(pattern).format(date);
    }
}
