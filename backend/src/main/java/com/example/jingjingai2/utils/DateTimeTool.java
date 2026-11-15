package com.example.jingjingai2.utils;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
public class DateTimeTool {
    @Tool(description = "获取当前的日期和时间。当用户问'现在几点'、'今天几号'、'今天星期几'时调用。")
    public String getCurrentTime(@ToolParam(description = "时区，例如 Asia/Shanghai") String timezone) {
        ZoneId zone = (timezone == null || timezone.isBlank()) ? ZoneId.of("Asia/Shanghai") : ZoneId.of(timezone);
        ZonedDateTime now = ZonedDateTime.now(zone);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss EEEE", Locale.CHINESE);
        return "当前时间：" + now.format(fmt);
    }
}
