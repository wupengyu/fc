package cn.daenx.myadmin.common.constant;

/**
 * 回调事件
 */
public class EventConstant {

    /**
     * 注入成功（10000）
     */
    public static final String injectSuccess = "10000";

    /**
     * 账号变动事件（10014）
     */
    public static final String wxidChange = "10014";

    /**
     * 收到群聊消息（10008）
     */
    public static final String recvMsgGroup = "10008";

    /**
     * 收到私聊消息（10009）
     */
    public static final String recvMsgFriend = "10009";

    /**
     * 自己发出消息（10010）
     */
    public static final String sendMsg = "10010";

    /**
     * 转账事件（10006）
     */
    public static final String transPay = "10006";

    /**
     * 撤回事件（10013）
     */
    public static final String revokeMsg = "10013";

    /**
     * 好友请求（10011）
     */
    public static final String friendReq = "10011";

    /**
     * 支付事件（10007）
     */
    public static final String eventPay = "10007";

    /**
     * 授权到期（99999）
     */
    public static final String authExpire = "99999";

    /**
     * 二维码收款事件（10015）
     */
    public static final String qrPay = "10015";

    /**
     * 群成员变动事件（10016）
     */
    public static final String groupMemberChanges = "10016";
}
