package cn.daenx.myadmin.modules.utils;

import lombok.extern.slf4j.Slf4j;

/**
 * 千寻微信框架Pro 文本代码工具类
 */
@Slf4j
public class QianXunText {

    /**
     * 艾特某人
     *
     * @param wxid
     * @return
     */
    public static String at(String wxid) {
        return "[@,wxid=" + wxid + ",nick=,isAuto=true]";
    }

    /**
     * 艾特所有人
     *
     * @return
     */
    public static String atAll() {
        return "[@,wxid=all,nick=,isAuto=true]";
    }

    /**
     * 换行
     *
     * @return
     */
    public static String br() {
        return "\r";
    }
}
