package com.example.sshproxy.payload;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class PayloadProcessor {

    public static int rotateIndex = 0;

    public static String processPayload(String template, String host, String port, String proxy, String userAgent) {
        String payload = template;

        // 1. Basic replacements
        payload = payload.replace("[crlf]", "\r\n");
        payload = payload.replace("[host]", host);
        payload = payload.replace("[rlb]", host);
        payload = payload.replace("[port]", port);

        // 2. Proxy placeholder
        if (proxy != null && !proxy.isEmpty()) {
            payload = payload.replace("[proxy]", proxy);
        }

        // 3. User-Agent
        if (userAgent == null || userAgent.isEmpty()) {
            userAgent = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36";
        }
        payload = payload.replace("[ua]", userAgent);

        // 4. Referer header support ([https/host] -> https://actual-host)
        payload = payload.replace("[https/host]", "https://" + host);

        // 5. Normalize raw newlines to HTTP/1.1 compliant \r\n
        //    This converts any \n that is NOT already preceded by \r into \r\n
        payload = payload.replaceAll("(?<!\\r)\\n", "\r\n");

        // 6. Sequential rotation [rotate=host1;host2;host3]
        Pattern rotatePattern = Pattern.compile("\\[rotate=([^\\]]+)\\]");
        Matcher rotateMatcher = rotatePattern.matcher(payload);
        if (rotateMatcher.find()) {
            String[] hosts = rotateMatcher.group(1).split(";");
            String selectedHost = hosts[rotateIndex % hosts.length];
            payload = payload.replace(rotateMatcher.group(0), selectedHost);
            // rotateIndex is incremented on failure in CustomVpnService
        }

        return payload;
    }

    // Splits payload by [split] tag
    public static String[] splitPayload(String payload) {
        return payload.split("\\[split\\]");
    }

    public static void resetRotateIndex() {
        rotateIndex = 0;
    }
            }
