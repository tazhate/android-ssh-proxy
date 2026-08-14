package hev.sockstun;

public class TProxyService {
    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
