package com.draw.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Properties;

/**
 * 负责加载和创建服务器配置文件 (server.properties)
 */
public class ServerConfig {
    private static final Logger logger = LoggerFactory.getLogger(ServerConfig.class);
    private static final String CONFIG_FILE_NAME = "server.properties";

    // 默认配置
    private static final String DEFAULT_HTTP_HOST = "0.0.0.0";
    private static final int DEFAULT_HTTP_PORT = 56678;
    private static final String DEFAULT_WS_HOST = "0.0.0.0";
    private static final int DEFAULT_WS_PORT = 12222;

    private String httpHost;
    private int httpPort;
    private String wsHost;
    private int wsPort;

    /**
     * 构造函数，自动加载或创建配置文件。
     */
    public ServerConfig() {
        loadProperties();
    }

    private void loadProperties() {
        File configFile = new File(CONFIG_FILE_NAME);
        Properties prop = new Properties();

        if (configFile.exists() && !configFile.isDirectory()) {
            logger.info("找到配置文件 '{}'，正在加载...", CONFIG_FILE_NAME);
            try (InputStream input = new FileInputStream(configFile)) {
                prop.load(new InputStreamReader(input, "UTF-8"));
                this.httpHost = prop.getProperty("http.host", DEFAULT_HTTP_HOST);
                this.httpPort = Integer.parseInt(prop.getProperty("http.port", String.valueOf(DEFAULT_HTTP_PORT)));
                this.wsHost = prop.getProperty("ws.host", DEFAULT_WS_HOST);
                this.wsPort = Integer.parseInt(prop.getProperty("ws.port", String.valueOf(DEFAULT_WS_PORT)));
                logger.info("配置加载成功！");
            } catch (IOException | NumberFormatException e) {
                logger.error("读取或解析配置文件时发生错误，将使用默认配置。", e);
                useDefaultValues();
            }
        } else {
            logger.warn("未找到配置文件 '{}'。将创建默认配置文件并使用默认值。", CONFIG_FILE_NAME);
            useDefaultValues();
            saveDefaultProperties();
        }
    }

    private void useDefaultValues() {
        this.httpHost = DEFAULT_HTTP_HOST;
        this.httpPort = DEFAULT_HTTP_PORT;
        this.wsHost = DEFAULT_WS_HOST;
        this.wsPort = DEFAULT_WS_PORT;
    }

    private void saveDefaultProperties() {
        Properties prop = new Properties();
        prop.setProperty("http.host", this.httpHost);
        prop.setProperty("http.port", String.valueOf(this.httpPort));
        prop.setProperty("ws.host", this.wsHost);
        prop.setProperty("ws.port", String.valueOf(this.wsPort));

        try (OutputStream output = new FileOutputStream(CONFIG_FILE_NAME)) {
            String comments = "你画我猜 服务器配置文件\n" +
                              "# http.host: HTTP服务监听的地址 (0.0.0.0 代表监听本机所有网络地址)\n" +
                              "# http.port: HTTP服务监听的端口\n" +
                              "# ws.host: WebSocket服务监听的地址 (0.0.0.0 代表监听本机所有网络地址)\n" +
                              "# ws.port: WebSocket服务监听的端口";
            prop.store(new OutputStreamWriter(output, "UTF-8"), comments);
            logger.info("已成功创建默认配置文件: {}", CONFIG_FILE_NAME);
        } catch (IOException e) {
            logger.error("创建默认配置文件时发生严重错误。", e);
        }
    }

    // Getters
    public String getHttpHost() { return httpHost; }
    public int getHttpPort() { return httpPort; }
    public String getWsHost() { return wsHost; }
    public int getWsPort() { return wsPort; }
}