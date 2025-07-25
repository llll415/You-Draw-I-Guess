package com.draw.server;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class WebSocketGameServer extends WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketGameServer.class);
    private final ConcurrentHashMap<WebSocket, String> pendingAuthentication = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WebSocket, String> playerNames = new ConcurrentHashMap<>();
    private final List<WebSocket> playerJoinOrder = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> WORD_LIST = List.of( "大象", "长颈鹿", "企鹅", "袋鼠", "熊猫", "狮子", "老虎", "海豚", "章鱼", "螃蟹", "蝴蝶", "蜻蜓", "猫头鹰", "啄木鸟", "火烈鸟", "孔雀", "变色龙", "鳄鱼", "蜗牛", "刺猬", "披萨", "汉堡", "薯条", "冰淇淋", "甜甜圈", "爆米花", "寿司", "牛角包", "草莓", "菠萝", "西瓜", "葡萄", "樱桃", "棒棒糖", "巧克力", "蛋糕", "方便面", "火锅", "烤串", "茶叶蛋", "手机", "笔记本电脑", "键盘", "鼠标", "耳机", "照相机", "电视", "空调", "冰箱", "洗衣机", "沙发", "台灯", "闹钟", "雨伞", "背包", "眼镜", "手表", "吉他", "钢琴", "小提琴", "马桶", "牙刷", "吹风机", "剪刀", "订书机", "红绿灯", "消防栓", "路灯", "秋千", "风筝", "画画", "跳舞", "唱歌", "阅读", "跑步", "游泳", "钓鱼", "睡觉", "打电话", "庆祝", "思考", "胜利", "爱心", "彩虹", "闪电", "龙卷风", "火山", "瀑布", "日出", "日落", "超级马里奥", "皮卡丘", "哆啦A梦", "奥特曼", "海绵宝宝", "蜘蛛侠", "钢铁侠", "孙悟空", "医生", "警察", "消防员", "老师", "宇航员", "程序员", "画家", "厨师");
    private final ConcurrentHashMap<WebSocket, String> currentWords = new ConcurrentHashMap<>();
    private WebSocket currentPlayer = null;
    private final ScheduledExecutorService statsBroadcaster = Executors.newSingleThreadScheduledExecutor();
    private final OperatingSystemMXBean osBean = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
    private final String osInfo;
    private final String javaInfo;

    public WebSocketGameServer(String host, int port) {
        super(new InetSocketAddress(host, port));
        this.osInfo = System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
        this.javaInfo = "Java " + System.getProperty("java.version");
    }

    @Override
    public void onStart() {
        logger.info("WebSocket 你画我猜服务器已在 {}:{} 成功启动！", getAddress().getHostString(), getPort());
        setConnectionLostTimeout(100);
        statsBroadcaster.scheduleAtFixedRate(this::broadcastServerStats, 0, 3, TimeUnit.SECONDS);
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        super.stop(timeout);
        statsBroadcaster.shutdownNow();
        logger.info("服务器已停止。");
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String token = UUID.randomUUID().toString();
        pendingAuthentication.put(conn, token);
        conn.send("AUTH_REQUEST:" + token);
        logger.info("新连接: {}。已发送认证请求。", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String playerName = playerNames.get(conn);
        if (playerName != null) {
            playerNames.remove(conn);
            currentWords.remove(conn);
            playerJoinOrder.remove(conn);
            logger.info("玩家 {} 已断开连接。", playerName);
            broadcastToAll("MESSAGE:玩家 " + playerName + " 离开了游戏。|PLAYERS:" + playerNames.size());
            updateHostAndGameStatus();
            if (conn.equals(currentPlayer)) {
                logger.info("绘画者已断开，开始新一轮。");
                nextTurn("系统（因绘画者离开）");
            }
        } else {
            pendingAuthentication.remove(conn);
            logger.warn("一个未认证的连接已断开: {}", conn.getRemoteSocketAddress());
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (message.startsWith("AUTH_VALIDATE:")) {
            handleAuthentication(conn, message);
            return;
        }
        if (message.startsWith("PING:")) {
            conn.send("PONG:" + message.substring(5));
            return;
        }
        if (!playerNames.containsKey(conn)) {
            logger.warn("收到来自未认证连接 {} 的非法消息，已忽略。", conn.getRemoteSocketAddress());
            return;
        }
        String playerName = playerNames.get(conn);
        switch (message.split(":")[0]) {
            case "START_GAME":
                if (!playerJoinOrder.isEmpty() && conn.equals(playerJoinOrder.get(0)) && currentPlayer == null) {
                    logger.info("房主 {} 开始了游戏。", playerName);
                    nextTurn("系统");
                }
                break;
            case "WORD_CHOSEN":
                if (conn.equals(currentPlayer)) {
                    String chosenWord = message.substring(12);
                    currentWords.put(conn, chosenWord);
                    logger.info("玩家 {} 选择了题目: {}", playerName, chosenWord);
                    currentPlayer.send("YOUR_TURN:" + chosenWord);
                    broadcastToOthers("MESSAGE:出题者已选好题目，游戏开始！", currentPlayer);
                }
                break;
            case "DRAW":
                broadcastToOthers(message, conn);
                break;
            case "GUESS":
                String guess = message.substring(6);
                logger.info("收到来自 {} 的猜测: {}", playerName, guess);
                checkGuess(conn, guess);
                break;
            case "CLEAR":
                 if (conn.equals(currentPlayer)) {
                    logger.info("绘画者 {} 清空了画板。", playerName);
                    broadcastToOthers("CLEAR", conn);
                }
                break;
            default:
                logger.warn("收到来自 {} 的未知类型消息: {}", playerName, message);
                break;
        }
    }

    private void broadcastServerStats() {
        if (playerNames.isEmpty()) return;
        double cpuLoad = osBean.getCpuLoad() * 100;
        long totalMemory = Runtime.getRuntime().totalMemory();
        long freeMemory = Runtime.getRuntime().freeMemory();
        long usedMemory = totalMemory - freeMemory;
        String statsJson = String.format("{\"os\":\"%s\",\"java\":\"%s\",\"cpu\":%.1f,\"mem_used\":%d,\"mem_total\":%d}", this.osInfo, this.javaInfo, cpuLoad < 0 ? 0 : cpuLoad, usedMemory / 1024 / 1024, totalMemory / 1024 / 1024);
        broadcastToAll("SERVER_STATS:" + statsJson);
    }

    private void handleAuthentication(WebSocket conn, String message) {
        String[] parts = message.substring(14).split(":", 2);
        if (parts.length != 2) { conn.close(1002, "Invalid auth format"); return; }
        String receivedToken = parts[0];
        String playerName = parts[1].trim();
        String expectedToken = pendingAuthentication.get(conn);
        if (expectedToken != null && expectedToken.equals(receivedToken)) {
            synchronized (playerNames) {
                if (playerNames.values().stream().anyMatch(name -> name.equalsIgnoreCase(playerName))) {
                    logger.warn("认证失败: 用户名 '{}' 已被占用。拒绝连接 {}", playerName, conn.getRemoteSocketAddress());
                    conn.send("MESSAGE:认证失败：该用户名已被使用！");
                    conn.close(1008, "Username already taken");
                } else {
                    logger.info("玩家 {} ({}) 认证成功。", playerName, conn.getRemoteSocketAddress());
                    pendingAuthentication.remove(conn);
                    playerNames.put(conn, playerName);
                    playerJoinOrder.add(conn);
                    broadcastToAll("MESSAGE:玩家 " + playerName + " 加入了游戏！|PLAYERS:" + playerNames.size());
                    updateHostAndGameStatus();
                }
            }
        } else {
            logger.warn("来自 {} 的认证失败（令牌不匹配或已过期），关闭连接。", conn.getRemoteSocketAddress());
            conn.close(1008, "Authentication failed");
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.error("连接 {} 发生错误: {}", (conn != null ? conn.getRemoteSocketAddress() : "未知"), ex.getMessage(), ex);
    }

    private synchronized void updateHostAndGameStatus() {
        if (currentPlayer != null) {
            broadcastToAll("HOST_STATUS:false:N/A");
            return;
        }
        if (playerJoinOrder.isEmpty()) {
            logger.info("没有玩家在线。");
            return;
        }
        WebSocket host = playerJoinOrder.get(0);
        String hostName = playerNames.get(host);
        for (WebSocket conn : playerNames.keySet()) {
            conn.send("HOST_STATUS:" + conn.equals(host) + ":" + hostName);
        }
        if (playerNames.size() >= 2) {
            logger.info("当前房主是: {}。人数满足条件，等待房主开始游戏。", hostName);
        } else {
            logger.info("当前房主是: {}。人数不足，游戏无法开始。", hostName);
        }
    }

    private synchronized void nextTurn(String winnerName) {
        String currentWord = (currentPlayer != null) ? currentWords.get(currentPlayer) : null;
        if (currentWord != null) {
            broadcastToAll("MESSAGE:恭喜 " + winnerName + " 猜对了！答案是: " + currentWord);
            logger.info("{} 猜对了，答案是 {}。准备开始下一轮。", winnerName, currentWord);
            currentWords.remove(currentPlayer);
        }
        if (playerNames.isEmpty()) { currentPlayer = null; logger.info("所有玩家已离开，游戏暂停。"); return; }
        List<WebSocket> players = new ArrayList<>(playerNames.keySet());
        Collections.shuffle(players);
        int currentIndex = players.indexOf(currentPlayer);
        currentPlayer = players.get((currentIndex + 1) % players.size());
        broadcastToAll("CLEAR");
        String drawerName = playerNames.get(currentPlayer);
        List<String> shuffledWords = new ArrayList<>(WORD_LIST);
        Collections.shuffle(shuffledWords);
        String choices = shuffledWords.stream().limit(3).collect(Collectors.joining(","));
        currentPlayer.send("CHOOSE_WORD:" + choices);
        logger.info("为玩家 {} 发送选词请求: {}", drawerName, choices);
        broadcastToAll("MESSAGE:--------------------------------");
        broadcastToAll("MESSAGE:下一轮由 " + drawerName + " 绘画，请等待其选择题目...");
        updateHostAndGameStatus();
    }

    private synchronized void checkGuess(WebSocket guesser, String guess) {
        if (guesser.equals(currentPlayer)) { guesser.send("MESSAGE:你是绘画者，不能猜测！"); return; }
        String correctWord = currentWords.get(currentPlayer);
        if (correctWord != null && guess != null && guess.trim().equalsIgnoreCase(correctWord)) {
            nextTurn(playerNames.get(guesser));
        } else if (correctWord != null) {
            broadcastToAll("MESSAGE:" + playerNames.get(guesser) + ": " + guess);
        }
    }

    public void broadcastToAll(String message) {
        synchronized (playerNames) {
            for (WebSocket conn : playerNames.keySet()) {
                conn.send(message);
            }
        }
    }

    private void broadcastToOthers(String message, WebSocket sender) {
        synchronized (playerNames) {
            for (WebSocket conn : playerNames.keySet()) {
                if (conn != null && !conn.equals(sender)) {
                    conn.send(message);
                }
            }
        }
    }

    public static void main(String[] args) {
        ServerConfig config = new ServerConfig();
        try {
            WebSocketGameServer wsServer = new WebSocketGameServer(config.getWsHost(), config.getWsPort());
            wsServer.start();
            HttpServer httpServer = createHttpServer(config);
            httpServer.start();
            logger.info("HTTP 服务器已在端口 {} 启动，请通过 http://{}:{} 访问。", config.getHttpPort(), config.getHttpHost(), config.getHttpPort());
        } catch (Exception e) {
            logger.error("启动服务器失败：", e);
        }
    }

    private static HttpServer createHttpServer(ServerConfig config) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.getHttpHost(), config.getHttpPort()), 0);

        server.createContext("/api/config", exchange -> {
            String jsonResponse = String.format("{\"ws_port\":%d}", config.getWsPort());
            byte[] responseBytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });

        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            String resourcePath = "/web" + path;
            try (InputStream resourceStream = WebSocketGameServer.class.getResourceAsStream(resourcePath)) {
                if (resourceStream == null) {
                    String response = "404 (Not Found)\n";
                    exchange.sendResponseHeaders(404, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                } else {
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = resourceStream.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
        });

        server.setExecutor(null);
        return server;
    }
}