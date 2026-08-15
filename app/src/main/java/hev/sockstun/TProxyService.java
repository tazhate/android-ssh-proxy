package hev.sockstun;

public class TProxyService {
    // Native method declarations
    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();

    // STATIC BLOCK – REQUIRED
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}