package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class OrderBufferService {

    private volatile List<OrderMessage> buffer = new ArrayList<>();

    private volatile long firstMessageTime = 0;

    private final Object swapLock = new Object();

    public void add(OrderMessage msg) {
        synchronized (swapLock) {
            buffer.add(msg);
            if (firstMessageTime == 0) {
                firstMessageTime = System.currentTimeMillis();
            }
        }
    }

    public void restoreMessages(List<OrderMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        synchronized (swapLock) {
            List<OrderMessage> restored = new ArrayList<>(messages.size() + buffer.size());
            restored.addAll(messages);
            restored.addAll(buffer);
            buffer = restored;
            if (firstMessageTime == 0) {
                firstMessageTime = System.currentTimeMillis();
            }
        }
    }

    public DrainResult drainIfReady(int triggerCount, long triggerWait) {
        synchronized (swapLock) {
            int size = buffer.size();
            if (size == 0) {
                return DrainResult.notReady(0, 0);
            }

            long waiting = firstMessageTime == 0 ? 0 : System.currentTimeMillis() - firstMessageTime;
            if (size < triggerCount && waiting < triggerWait) {
                return DrainResult.notReady(size, waiting);
            }

            List<OrderMessage> snapshot = buffer;
            buffer = new ArrayList<>();
            firstMessageTime = 0;

            return new DrainResult(snapshot, size, waiting);
        }
    }

    public int pendingCount() {
        synchronized (swapLock) {
            return buffer.size();
        }
    }

    public record DrainResult(List<OrderMessage> messages, int size, long waitingMs) {
        public static DrainResult notReady(int size, long waitingMs) {
            return new DrainResult(Collections.emptyList(), size, waitingMs);
        }

        public boolean ready() {
            return !messages.isEmpty();
        }
    }
}
