package cn.daenx.myadmin.modules.domain.event;

import lombok.Data;

import java.util.List;

@Data
public class RecvMsgReqVo {
    /**
     * 收到这条消息的13位现行时间戳
     */
    private String timeStamp;

    /**
     * 来源类型：1|私聊 2|群聊 3|公众号
     */
    private Integer fromType;

    /**
     * 消息类型：1|文本 3|图片 34|语音 42|名片 43|视频 47|动态表情 48|地理位置 49|分享链接或附件
     * 2001|红包 2002|小程序 2003|群邀请 10000|系统消息
     */
    private Integer msgType;

    /**
     * 消息来源：0|别人发送 1|自己发送
     */
    private Integer msgSource;

    /**
     * fromType=1时为好友wxid，fromType=2时为群wxid，fromType=3时公众号wxid
     */
    private String fromWxid;

    /**
     * 仅fromType=2时有效，为群内发言人wxid
     */
    private String finalFromWxid;

    /**
     * 仅fromType=2时有效，为消息中@人wxid列表
     */
    private List<String> atWxidList;

    /**
     * 仅fromType=2时有效，固定值0
     */
    private Integer silence;

    /**
     * 仅fromType=2时有效，群成员数量
     */
    private Integer membercount;

    /**
     * 消息签名
     */
    private String signature;

    /**
     * 消息内容
     */
    private String msg;

    /**
     * 消息ID
     */
    private String msgId;

    /**
     * 消息发送请求ID，此事件内为空
     */
    private String sendId;
}
