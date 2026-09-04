package com.eventsphere.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class IpUtil {
    public static String getLocalIpAddress() {
        String overrideIp = EnvLoader.get("SERVER_IP", "");
        if (overrideIp != null && !overrideIp.trim().isEmpty()) {
            return overrideIp.trim();
        }

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String lowerName = iface.getDisplayName().toLowerCase();
                String name = iface.getName().toLowerCase();

                if (lowerName.contains("wi-fi") || lowerName.contains("wifi") || lowerName.contains("ethernet") ||
                    name.contains("wlan") || name.contains("eth")) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }

            // Fallback
            interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to resolve network interface IP: " + e.getMessage());
        }
        return "localhost";
    }
}
