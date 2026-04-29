package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.Message;

import java.util.List;

public interface MessageBufferService {

    void addMessage(Message msg);

    List<Message> flushAndClear();

    void restoreMessages(List<Message> messages);

    int pendingCount();
}
