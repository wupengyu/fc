package cn.daenx.myadmin.modules.domain.dto;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI解析结果
 * 新结构：支持AI判断是否为有效报单
 */
@Data
public class AiParseResult {

    /**
     * 消息索引（对应输入的消息序号）
     */
    private int index;

    /**
     * AI判断是否为有效报单
     */
    private boolean valid;

    /**
     * 解析状态: SUCCESS(成功) / FAIL(是报单但解析失败) / SKIP(非报单跳过)
     */
    private String status;

    /**
     * 失败或跳过的原因
     */
    private String reason;

    /**
     * 解析出的数据（仅valid=true且status=SUCCESS时有值）
     */
    private ParsedData data;

    /**
     * AI原始响应JSON（用于传递到持久化层保存，不参与业务逻辑）
     */
    private String rawAiResponse;

    // ========== 兼容旧字段（过渡期保留） ==========

    /**
     * @deprecated 使用 !valid || !"SUCCESS".equals(status) 代替
     */
    @Deprecated
    private boolean failed;

    /**
     * @deprecated 使用 reason 代替
     */
    @Deprecated
    private String error;

    /**
     * @deprecated 使用 data 代替，新结构每条消息只对应一个解析结果
     */
    @Deprecated
    private List<ParsedOrder> orders;

    // ========== 便捷方法 ==========

    /**
     * 是否解析成功（是有效报单且解析成功）
     */
    public boolean isSuccess() {
        return valid && "SUCCESS".equals(status);
    }

    /**
     * 是否跳过（非报单）
     */
    public boolean isSkip() {
        return "SKIP".equals(status);
    }

    /**
     * 是否失败（是报单但解析失败）
     */
    public boolean isFailed() {
        // 兼容旧逻辑
        if (failed) return true;
        return valid && "FAIL".equals(status);
    }

    /**
     * 获取错误/原因信息
     */
    public String getErrorOrReason() {
        return reason != null ? reason : error;
    }

    /**
     * 新的解析数据结构
     */
    @Data
    public static class ParsedData {
        /**
         * 彩种类别: FC(福彩) / TC(体彩)
         */
        private String category;

        /**
         * 游戏类型: 3D / SSQ / P3 / P5 / DLT / QLQ
         */
        private String game;

        /**
         * 玩法: 直选 / 组三 / 组六 / 复式
         */
        private String play;

        /**
         * 号码区域: MAIN / RED / BLUE / FRONT / BACK
         */
        private String zone;

        /**
         * 号码列表
         */
        private List<String> numbers;

        /**
         * 注数
         */
        private int bet;

        /**
         * 倍数
         */
        private int multiple = 1;

        /**
         * 金额（元）
         */
        private BigDecimal amount;
    }

    /**
     * @deprecated 使用 ParsedData 代替
     */
    @Deprecated
    @Data
    public static class ParsedOrder {
        @JSONField(name = "lottery_category")
        private String lotteryCategory;

        @JSONField(name = "game_type")
        private String gameType;

        @JSONField(name = "play_type")
        private String playType;

        @JSONField(name = "number_zone")
        private String numberZone;

        private List<String> numbers;

        @JSONField(name = "bet_count")
        private int betCount;

        @JSONField(name = "group_count")
        private int groupCount;

        private int multiple = 1;

        private BigDecimal amount;

        private String rawText;
    }
}
