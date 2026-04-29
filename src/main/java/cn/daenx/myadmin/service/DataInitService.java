package cn.daenx.myadmin.service;

import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.mapper.MessageMapper;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class DataInitService {

    @Autowired
    private MessageMapper messageMapper;

    // @PostConstruct - 已禁用：消息现在直接入库，不再从 msg.txt 加载
    public void init() {
        // 初始化逻辑已禁用
        log.info("DataInitService 初始化已禁用，消息直接入库");
    }
}
