package hev.sockstun;

public class TProxyService {
    // Start the VPN tunnel (receives YAML config path and TUN file descriptor)
    public static native void TProxyStartService(String configPath, int fd);
    
    // Stop the VPN tunnel
    public static native void TProxyStopService();
    
    // Check if the tunnel is running
    public static native boolean TProxyIsRunning();
    
    // Get traffic statistics (returns long[]: [tx_bytes, tx_packets, rx_bytes, rx_packets])
    public static native long[] TProxyGetStats();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }
}
