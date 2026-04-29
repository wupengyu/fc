package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.mapper.MessageMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/check")
public class MessageCheckController {

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${redis.queue-name:wechat_messages}")
    private String queueName;

    @GetMapping("/messages")
    public Map<String, Object> checkMessages() {
        LocalDateTime today730PM = LocalDateTime.now()
                .withHour(19)
                .withMinute(30)
                .withSecond(0)
                .withNano(0);

        QueryWrapper<cn.daenx.myadmin.entity.Message> qw = new QueryWrapper<>();
        qw.ge("received_at", today730PM);
        long dbCount = messageMapper.selectCount(qw);

        Long redisCount = redisTemplate.opsForList().size(queueName);
        if (redisCount == null) redisCount = 0L;

        Map<String, Object> result = new HashMap<>();
        result.put("timeRange", today730PM.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " 至今");
        result.put("dbCount", dbCount);
        result.put("redisQueueCount", redisCount);
        result.put("total", dbCount + redisCount);
        result.put("status", redisCount == 0 ? "所有消息已入库" : "Redis队列中还有 " + redisCount + " 条待处理");

        log.info("消息检查结果: DB={}, Redis={}, Total={}", dbCount, redisCount, dbCount + redisCount);
        return result;
    }
}
