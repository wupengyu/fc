package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.common.vo.Result;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.daenx.myadmin.modules.service.OrderBufferService;
import cn.daenx.myadmin.modules.service.OrderWindowService;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api")
public class OrderController {

    @Autowired
    private OrderBufferService orderBufferService;

    @Autowired
    private OrderWindowService orderWindowService;

    @PostMapping("/orders/raw")
    public Result submitOrder(@RequestBody JSONObject body) {
        String rawText = normalize(body.getString("rawText"));
        if (rawText == null) {
            return Result.error(10001, "报单文本为空");
        }

        String source = normalize(body.getString("source"));
        if (source == null) {
            source = OrderConstant.SOURCE_API;
        }
        String msgId = normalize(body.getString("msgId"));
        String requestFingerprint = normalize(body.getString("fingerprint"));
        String fromWxid = normalize(body.getString("fromWxid"));
        String senderWxid = normalize(body.getString("senderWxid"));

        String receivedAtStr = normalize(body.getString("receivedAt"));
        LocalDateTime receivedAt = receivedAtStr != null
                ? LocalDateTime.parse(receivedAtStr)
                : LocalDateTime.now();

        OrderMessage msg = new OrderMessage();
        msg.setMsgId(msgId);
        msg.setSource(source);
        msg.setFromWxid(fromWxid);
        msg.setSenderWxid(senderWxid);
        msg.setRawText(rawText);
        msg.setReceivedAt(receivedAt);
        msg.setFingerprint(resolveFingerprint(requestFingerprint, source, msgId, fromWxid, senderWxid, rawText, receivedAt));

        JSONObject data = new JSONObject();
        data.put("fingerprint", msg.getFingerprint());
        data.put("window", orderWindowService.getWindowText());

        if (!orderWindowService.isInOrderWindow(receivedAt)) {
            log.info("raw order request skipped outside order window, msgId={}, source={}, receivedAt={}, window={}",
                    msgId, source, receivedAt, orderWindowService.getWindowText());
            data.put("buffered", false);
            data.put("reason", "outside_order_window");
            return Result.ok(data);
        }

        orderBufferService.add(msg);
        data.put("buffered", true);

        return Result.ok(data);
    }

    private String resolveFingerprint(String requestFingerprint,
                                      String source,
                                      String msgId,
                                      String fromWxid,
                                      String senderWxid,
                                      String rawText,
                                      LocalDateTime receivedAt) {
        if (requestFingerprint != null) {
            return requestFingerprint;
        }
        String seed = msgId != null
                ? source + "|" + msgId
                : source + "|" +
                (fromWxid != null ? fromWxid : "") + "|" +
                (senderWxid != null ? senderWxid : "") + "|" +
                rawText + "|" +
                receivedAt;
        return DigestUtil.sha256Hex(seed);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
