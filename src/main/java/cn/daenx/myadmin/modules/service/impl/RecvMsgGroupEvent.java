package cn.daenx.myadmin.modules.service.impl;

import cn.daenx.myadmin.common.constant.EventConstant;
import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.modules.domain.BaseEventVo;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.daenx.myadmin.modules.domain.event.RecvMsgReqVo;
import cn.daenx.myadmin.modules.service.EventHandleService;
import cn.daenx.myadmin.modules.service.MessageBufferService;
import cn.daenx.myadmin.modules.service.MessageIngressService;
import cn.daenx.myadmin.modules.service.OrderBufferService;
import cn.daenx.myadmin.modules.service.OrderWindowService;
import cn.daenx.myadmin.modules.utils.QianXunApi;
import cn.daenx.myadmin.modules.utils.QianXunText;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service("event:" + EventConstant.recvMsgGroup)
public class RecvMsgGroupEvent implements EventHandleService {

    private static final String MESSAGE_SOURCE = "WECHAT_CALLBACK";
    private static final String HELLO = "\u4f60\u597d";
    private static final String HELLO_REPLY = " \uD83D\uDE00 \u4f60\u4e5f\u597d";
    private static final String HELLO_SUFFIX = "\u6700\u8fd1\u600e\u4e48\u6837\uff1f";

    @Value("${target-group-wxid}")
    private String targetGroupWxid;

    @Autowired
    private MessageBufferService messageBufferService;

    @Autowired
    private MessageIngressService messageIngressService;

    @Autowired
    private OrderBufferService orderBufferService;

    @Autowired
    private OrderWindowService orderWindowService;

    @Override
    public void handle(BaseEventVo baseEventVo, JSONObject jsonObject) {
        RecvMsgReqVo data = jsonObject.toJavaObject(RecvMsgReqVo.class);
        log.info("group message received, bot={}, group={}, sender={}, content={}",
                baseEventVo.getWxid(), data.getFromWxid(), data.getFinalFromWxid(), data.getMsg());

        if (targetGroupWxid.equals(data.getFromWxid())) {
            Message rawMessage = messageIngressService.buildMessage(data, MESSAGE_SOURCE, jsonObject.toJSONString());
            MessageIngressService.IngestResult ingestResult = messageIngressService.ingest(rawMessage);
            if (ingestResult == MessageIngressService.IngestResult.FAILED) {
                messageBufferService.addMessage(rawMessage);
                log.warn("raw message insert failed, moved to retry buffer, msgId={}, fingerprint={}",
                        data.getMsgId(), rawMessage.getFingerprint());
            } else if (ingestResult == MessageIngressService.IngestResult.DUPLICATE) {
                log.info("duplicate raw message skipped, msgId={}, fingerprint={}",
                        data.getMsgId(), rawMessage.getFingerprint());
                return;
            } else {
                log.info("raw message stored, id={}, msgId={}, fingerprint={}",
                        rawMessage.getId(), data.getMsgId(), rawMessage.getFingerprint());
            }

            LocalDateTime msgTime = rawMessage.getReceivedAt() != null
                    ? rawMessage.getReceivedAt()
                    : LocalDateTime.now();

            if (orderWindowService.isInOrderWindow(msgTime)) {
                OrderMessage orderMsg = new OrderMessage();
                orderMsg.setMsgId(data.getMsgId());
                orderMsg.setSource(MESSAGE_SOURCE);
                orderMsg.setFromWxid(data.getFromWxid());
                orderMsg.setSenderWxid(data.getFinalFromWxid());
                orderMsg.setRawText(data.getMsg());
                orderMsg.setFingerprint(messageIngressService.buildBusinessFingerprint(data));
                orderMsg.setReceivedAt(msgTime);
                orderBufferService.add(orderMsg);
            } else {
                log.debug("message out of order window, msgId={}, time={}", data.getMsgId(), msgTime);
            }
        }

        if (HELLO.equals(data.getMsg())) {
            String msg = QianXunText.at(data.getFinalFromWxid()) + HELLO_REPLY +
                    QianXunText.br() + HELLO_SUFFIX;
            QianXunApi.sendText(baseEventVo.getWxid(), data.getFromWxid(), msg);
        }
    }
}
