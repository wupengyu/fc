package cn.daenx.myadmin.modules.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@Service
public class OrderWindowService {

    private static final DateTimeFormatter WINDOW_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Value("${order.window-start:${order.reparse-runtime-start:19:30:00}}")
    private String windowStartText;

    @Value("${order.window-end:${order.reparse-runtime-end:21:35:00}}")
    private String windowEndText;

    public boolean isInOrderWindow(LocalDateTime dateTime) {
        if (dateTime == null) {
            return isNowInOrderWindow();
        }
        return isInOrderWindow(dateTime.toLocalTime());
    }

    public boolean isInOrderWindow(LocalTime time) {
        if (time == null) {
            return false;
        }
        LocalTime start = getWindowStart();
        LocalTime end = getWindowEnd();
        if (!start.isAfter(end)) {
            return !time.isBefore(start) && !time.isAfter(end);
        }
        return !time.isBefore(start) || !time.isAfter(end);
    }

    public boolean isNowInOrderWindow() {
        return isInOrderWindow(LocalTime.now());
    }

    public LocalTime getWindowStart() {
        return LocalTime.parse(windowStartText);
    }

    public LocalTime getWindowEnd() {
        return LocalTime.parse(windowEndText);
    }

    public String getWindowText() {
        return WINDOW_FORMATTER.format(getWindowStart()) + "-" + WINDOW_FORMATTER.format(getWindowEnd());
    }
}
