package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_ai_call_log")
public class AiCallLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 请求批次条数 */
    private Integer batchCount;
    /** 发送给AI的system消息 */
    private String systemMessage;
    /** 发送给AI的user消息 */
    private String userMessage;
    /** AI原始响应内容 */
    private String aiResponse;
    /** API耗时(毫秒) */
    private Long latencyMs;
    /** 输入token数 */
    private Integer inputTokens;
    /** 输出token数 */
    private Integer outputTokens;
    /** 总token数 */
    private Integer totalTokens;
    /** 期号 */
    private String issueKey;
    /** 是否成功 */
    private Integer success;
    /** 错误信息 */
    private String errorMsg;
    private LocalDateTime createdAt;
}
