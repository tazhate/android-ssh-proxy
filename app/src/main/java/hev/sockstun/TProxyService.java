package hev.sockstun;

public class TProxyService {
    // IMPORTANT: The signature must match the .so exactly.
    // The sockstun library uses boolean return, but some builds use void.
    // We'll use void to match the error signature, then test.
    // If you get a different error, switch to boolean.
    public static native void TProxyStartService(String configPath, int fd);
    public static native void TProxyStopService();
    public static native boolean TProxyIsRunning();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
