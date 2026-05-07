package cn.daenx.myadmin.server.open;

/**
 * Legacy HTTP callback receiver has been intentionally removed from Spring registration.
 * Redis is the only active WeChat message source; see {@link DisabledCallBackController}.
 */
public final class CallBackController {
    private CallBackController() {
    }
}
