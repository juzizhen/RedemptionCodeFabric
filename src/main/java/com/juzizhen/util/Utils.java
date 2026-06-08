package com.juzizhen.util;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;

public class Utils {

    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成指定长度的随机字符串
     * @param length 字符串长度
     * @return 随机字符串
     */
    public static String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CHARACTERS.charAt(RANDOM.nextInt(CHARACTERS.length())));
        }
        return sb.toString();
    }

    /**
     * 检查端口是否可用
     * @param port 要检测的端口
     * @return true 表示可用，false 表示已被占用
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从起始端口开始查找第一个可用端口
     * @param startPort 起始端口
     * @param maxPort 最大端口范围
     * @return 可用端口号，如果没有找到返回 -1
     */
    public static int findAvailablePort(int startPort, int maxPort) {
        for (int port = startPort; port <= maxPort; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }
}
