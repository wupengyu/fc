package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.mapper.MessageMapper;
import cn.daenx.myadmin.modules.service.RedisClientService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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
    private RedisClientService redisClientService;

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

        long redisCount = redisClientService.queueSize();
        long redisProcessingCount = redisClientService.processingQueueSize();
        long pendingRedisCount = redisCount + redisProcessingCount;

        Map<String, Object> result = new HashMap<>();
        result.put("timeRange", today730PM.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " 至今");
        result.put("dbCount", dbCount);
        result.put("redisQueueCount", redisCount);
        result.put("redisProcessingQueueCount", redisProcessingCount);
        result.put("total", dbCount + pendingRedisCount);
        result.put("status", pendingRedisCount == 0
                ? "所有消息已入库"
                : "Redis队列中还有 " + redisCount + " 条待处理，processing 中 " + redisProcessingCount + " 条");

        log.info("消息检查结果: DB={}, Redis={}, Processing={}, Total={}",
                dbCount, redisCount, redisProcessingCount, dbCount + pendingRedisCount);
        return result;
    }
}
