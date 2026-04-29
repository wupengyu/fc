package cn.daenx.myadmin.modules.service.impl;

import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.modules.service.MessageBufferService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MessageBufferServiceImpl implements MessageBufferService {

    private volatile List<Message> buffer = new ArrayList<>();
    private final Object swapLock = new Object();

    @Override
    public void addMessage(Message msg) {
        synchronized (swapLock) {
            buffer.add(msg);
        }
    }

    @Override
    public List<Message> flushAndClear() {
        synchronized (swapLock) {
            List<Message> snapshot = buffer;
            buffer = new ArrayList<>();
            return snapshot;
        }
    }

    @Override
    public void restoreMessages(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        synchronized (swapLock) {
            List<Message> restored = new ArrayList<>(messages.size() + buffer.size());
            restored.addAll(messages);
            restored.addAll(buffer);
            buffer = restored;
        }
    }

    @Override
    public int pendingCount() {
        synchronized (swapLock) {
            return buffer.size();
        }
    }
}
