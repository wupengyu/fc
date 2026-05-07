package cn.daenx.myadmin.common.constant;

public class OrderConstant {

    public static final String SOURCE_WECHAT = "WECHAT";
    public static final String SOURCE_WECHAT_REDIS = "WECHAT_REDIS";
    public static final String SOURCE_WECHAT_SSE = "WECHAT_SSE";
    public static final String SOURCE_WECHAT_CALLBACK = "WECHAT_CALLBACK";
    public static final String SOURCE_API = "API";

    public static final int PARSE_STATUS_SUCCESS = 1;
    public static final int PARSE_STATUS_PARTIAL = 2;
    public static final int PARSE_STATUS_FAILED = 3;
    public static final int PARSE_STATUS_SKIPPED = 4;  // 非报单跳过

    public static final int STAT_NOT_APPLIED = 0;
    public static final int STAT_APPLIED = 1;

    public static final int EFFECTIVE = 1;
    public static final int NOT_EFFECTIVE = 0;

    public static final String ZONE_MAIN = "MAIN";

    public static final String ALLOC_SPLIT = "SPLIT";
}
