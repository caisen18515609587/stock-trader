package com.stocktrader.util;

import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.ClosedPosition;
import com.stocktrader.model.Portfolio;
import com.stocktrader.model.StrategyConfig;
import com.stocktrader.model.User;
import com.stocktrader.trading.AccountPersistence;
import com.stocktrader.trading.AuctionTrader;
import com.stocktrader.trading.AutoTrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 多用户交易平台 Web 服务器
 * <p>
 * 页面路由：
 *   GET  /            → 登录页（未登录）/ 控制台（已登录）
 *   GET  /register    → 注册页
 *   POST /register    → 提交注册
 *   POST /login       → 提交登录
 *   GET  /logout      → 退出登录
 *   GET  /dashboard   → 用户控制台（账户状态、持仓、运行情况）
 *   GET  /strategy    → 策略配置页
 *   POST /strategy    → 提交策略配置
 *   POST /start       → 启动自动交易
 *   POST /stop        → 停止自动交易
 *   GET  /admin       → 管理员页（查看所有用户）
 */
public class PlatformServer {

    private static final Logger log = LoggerFactory.getLogger(PlatformServer.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final int port;
    private final UserService userService;
    private final StockDataProvider dataProvider;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // userId -> AutoTrader 实例（每用户独立）
    private final Map<String, AutoTrader> userTraders = new ConcurrentHashMap<>();
    // userId -> AuctionTrader 实例（竞价封板策略专用）
    private final Map<String, AuctionTrader> auctionTraders = new ConcurrentHashMap<>();

    public PlatformServer(int port, UserService userService, StockDataProvider dataProvider) {
        this.port = port;
        this.userService = userService;
        this.dataProvider = dataProvider;
        this.executor = Executors.newCachedThreadPool();
    }

    public void start() {
        running.set(true);
        // ===== 【需求2】自动为所有 ACTIVE 用户启动交易 =====
        autoStartActiveUsers();

        executor.submit(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                log.info("🌐 平台服务器已启动: http://localhost:{}", port);
                while (running.get()) {
                    try {
                        Socket client = ss.accept();
                        executor.submit(() -> handle(client));
                    } catch (IOException e) {
                        if (running.get()) log.error("连接异常: {}", e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.error("平台服务器启动失败（端口{}占用？）: {}", port, e.getMessage());
            }
        });
    }

    /**
     * 【需求2】应用启动时自动为所有正常状态用户开启自动交易。
     * 只启动那些尚未运行的用户，避免重复启动。
     */
    private void autoStartActiveUsers() {
        List<com.stocktrader.model.User> allUsers = userService.getStore().findAll();
        int started = 0;
        for (com.stocktrader.model.User u : allUsers) {
            if (u.getStatus() != com.stocktrader.model.User.UserStatus.ACTIVE) continue;
            if (userTraders.containsKey(u.getUserId())) continue; // 已运行 (AutoTrader)
            if (auctionTraders.containsKey(u.getUserId())) continue; // 已运行 (AuctionTrader)
            try {
                handleStart(u);
                started++;
                log.info("[自动启动] 用户 {} ({}) 交易已自动启动", u.getUsername(), u.getStrategyType().getDesc());
            } catch (Exception e) {
                log.warn("[自动启动] 用户 {} 启动失败: {}", u.getUsername(), e.getMessage());
            }
        }
        log.info("[自动启动] 共自动启动 {} 个用户的交易程序", started);
    }

    public void stop() {
        running.set(false);
        // 优雅停止所有 AuctionTrader（原代码遗漏！）
        auctionTraders.values().forEach(t -> { try { t.stopTrading(); } catch (Exception ignored) {} });
        auctionTraders.clear();
        // 优雅停止所有 AutoTrader
        userTraders.values().forEach(t -> { try { t.stopTrading(); } catch (Exception ignored) {} });
        userTraders.clear();
        executor.shutdownNow();
    }

    // =================== 请求处理 ===================

    private void handle(Socket client) {
        try (InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.trim().isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String fullPath = parts[1];

            // 读取请求头
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(),
                        line.substring(colon + 1).trim());
            }

            // 读取Cookie（提取session）
            String cookie = headers.getOrDefault("cookie", "");
            String token = extractCookie(cookie, "session");

            // 读取请求体（POST）
            Map<String, String> postParams = new LinkedHashMap<>();
            if ("POST".equals(method)) {
                int contentLength = 0;
                try { contentLength = Integer.parseInt(headers.getOrDefault("content-length", "0")); }
                catch (Exception ignored) {}
                // 防止 Content-Length 过大导致 OOM（1MB 上限）
                if (contentLength > 1024 * 1024) { contentLength = 1024 * 1024; }
                if (contentLength > 0) {
                    char[] body = new char[contentLength];
                    // 循环读取确保读满（单次 read 可能不返回全部内容）
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int n = reader.read(body, totalRead, contentLength - totalRead);
                        if (n < 0) break;
                        totalRead += n;
                    }
                    postParams = parseQuery(new String(body, 0, totalRead));
                }
            }

            // 解析路径
            String path = fullPath.contains("?") ? fullPath.substring(0, fullPath.indexOf('?')) : fullPath;
            Map<String, String> queryParams = fullPath.contains("?")
                    ? parseQuery(fullPath.substring(fullPath.indexOf('?') + 1))
                    : new LinkedHashMap<>();

            User user = userService.getSessionUser(token);

            // 路由
            HttpResponse resp;
            switch (path) {
                case "/register":
                    resp = "POST".equals(method)
                            ? handleRegisterPost(postParams)
                            : buildPage(null, pageRegister());
                    break;
                case "/login":
                    resp = "POST".equals(method)
                            ? handleLoginPost(postParams)
                            : buildPage(null, pageLogin(""));
                    break;
                case "/logout":
                    userService.logout(token);
                    resp = redirect("/");
                    resp.clearCookie = true;
                    break;
                case "/dashboard":
                    resp = user != null
                            ? buildPage(user, pageDashboard(user))
                            : redirect("/");
                    break;
                case "/strategy":
                    if (user == null) { resp = redirect("/"); break; }
                    resp = "POST".equals(method)
                            ? handleStrategyPost(user, postParams)
                            : buildPage(user, pageStrategy(user));
                    break;
                case "/start":
                    resp = user != null && "POST".equals(method)
                            ? handleStart(user)
                            : redirect("/dashboard");
                    break;
                case "/stop":
                    resp = user != null && "POST".equals(method)
                            ? handleStop(user)
                            : redirect("/dashboard");
                    break;
                case "/wechat":
                    if (user == null) { resp = redirect("/"); break; }
                    resp = "POST".equals(method)
                            ? handleWechatPost(user, postParams)
                            : redirect("/dashboard");
                    break;
                case "/trades":
                    resp = user != null
                            ? buildPage(user, pageTrades(user))
                            : redirect("/");
                    break;
                case "/stats":
                    resp = user != null
                            ? buildPage(user, pageStats(user))
                            : redirect("/");
                    break;
                case "/scan-interval":
                    resp = user != null && "POST".equals(method)
                            ? handleScanIntervalPost(user, postParams)
                            : redirect("/dashboard");
                    break;
                case "/rollback-records":
                    resp = user != null
                            ? buildPage(user, pageRollbackRecords(user))
                            : redirect("/");
                    break;
                case "/admin":
                    if (user == null) { resp = redirect("/"); break; }
                    if (!user.isSuperAdmin()) { resp = redirect("/dashboard"); break; }
                    if ("POST".equals(method)) {
                        resp = handleAdminPost(user, postParams, queryParams);
                    } else {
                        resp = buildPage(user, pageAdmin(user));
                    }
                    break;
                default:
                    resp = user != null ? redirect("/dashboard") : buildPage(null, pageLogin(""));
                    break;
            }

            // 写响应
            StringBuilder httpResp = new StringBuilder();
            httpResp.append("HTTP/1.1 ").append(resp.status).append("\r\n");
            httpResp.append("Content-Type: text/html; charset=UTF-8\r\n");
            if (resp.setCookie != null) httpResp.append("Set-Cookie: ").append(resp.setCookie).append("\r\n");
            if (resp.clearCookie) httpResp.append("Set-Cookie: session=; Max-Age=0; Path=/\r\n");
            if (resp.location != null) httpResp.append("Location: ").append(resp.location).append("\r\n");
            byte[] bodyBytes = resp.body != null ? resp.body.getBytes(StandardCharsets.UTF_8) : new byte[0];
            httpResp.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            httpResp.append("Connection: close\r\n\r\n");
            out.write(httpResp.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        } catch (Exception e) {
            log.debug("处理请求异常: {}", e.getMessage());
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    // =================== 业务处理 ===================

    private HttpResponse handleRegisterPost(Map<String, String> p) {
        String username = p.getOrDefault("username", "").trim();
        String password = p.getOrDefault("password", "").trim();
        String nickname = p.getOrDefault("nickname", "").trim();
        double capital = parseDouble(p.getOrDefault("capital", "100000"), 100000);
        String strategyStr = p.getOrDefault("strategy", "DAY_TRADE");
        User.StrategyType st;
        try { st = User.StrategyType.valueOf(strategyStr); } catch (Exception e) { st = User.StrategyType.DAY_TRADE; }

        // 超管账户名保护：如果该用户名是超管，普通注册渠道禁止占用
        User existingCheck = userService.getStore().findByUsername(username);
        if (existingCheck != null && existingCheck.isSuperAdmin()) {
            return buildPage(null, pageRegister() + alertHtml("danger", "用户名不可用，请换一个"));
        }

        UserService.RegisterResult r = userService.register(username, password, nickname, capital, st);
        if (!r.success) {
            return buildPage(null, pageRegister() + alertHtml("danger", r.message));
        }
        // 注册成功，自动登录
        UserService.LoginResult lr = userService.login(username, password);
        HttpResponse resp = redirect("/dashboard");
        if (lr.success) resp.setCookie = "session=" + lr.sessionToken + "; Path=/; HttpOnly";
        return resp;
    }

    private HttpResponse handleLoginPost(Map<String, String> p) {
        String username = p.getOrDefault("username", "").trim();
        String password = p.getOrDefault("password", "").trim();
        UserService.LoginResult r = userService.login(username, password);
        if (!r.success) {
            return buildPage(null, pageLogin(r.message));
        }
        HttpResponse resp = redirect("/dashboard");
        resp.setCookie = "session=" + r.sessionToken + "; Path=/; HttpOnly";
        return resp;
    }

    private HttpResponse handleStrategyPost(User user, Map<String, String> p) {
        String strategyStr = p.getOrDefault("strategyType", "DAY_TRADE");
        String customDesc = p.getOrDefault("customDesc", "").trim();
        User.StrategyType st;
        try { st = User.StrategyType.valueOf(strategyStr); } catch (Exception e) { st = user.getStrategyType(); }

        Map<String, String> overrides = new LinkedHashMap<>();
        String[] keys = {"stopLoss", "takeProfitHalf", "takeProfitFull", "maxPosition", "maxPositions", "topN", "minScore", "scanInterval"};
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.trim().isEmpty()) overrides.put(k, v.trim());
        }

        userService.updateStrategy(user.getUserId(), st, customDesc, overrides);

        // 如果交易中，重启（AutoTrader 或 AuctionTrader 均重启）
        if (userTraders.containsKey(user.getUserId())
                || auctionTraders.containsKey(user.getUserId())) {
            handleStop(user);
            handleStart(userService.getStore().findById(user.getUserId()));
        }

        HttpResponse resp = redirect("/dashboard");
        return resp;
    }

    private HttpResponse handleWechatPost(User user, Map<String, String> p) {
        String openId  = p.getOrDefault("openId",  "").trim();
        String sendKey = p.getOrDefault("sendKey", "").trim();
        userService.updateWechatConfig(user.getUserId(), openId, sendKey);
        return redirect("/dashboard");
    }

    /**
     * 动态调整扫描间隔（运行时生效，无需重启交易）
     */
    private HttpResponse handleScanIntervalPost(User user, Map<String, String> p) {
        String valStr = p.getOrDefault("scanInterval", "").trim();
        if (valStr.isEmpty()) return redirect("/dashboard");
        try {
            int newInterval = Integer.parseInt(valStr);
            AutoTrader trader = userTraders.get(user.getUserId());
            if (trader != null) {
                trader.adjustScanInterval(newInterval);
                log.info("用户 {} 动态调整扫描间隔为 {} 分钟", user.getUsername(), newInterval);
            } else {
                // 未运行中时只修改策略配置里的扫描间隔，下次启动生效
                StrategyConfig sc = userService.getStrategyConfig(user.getUserId());
                Map<String, String> overrides = new java.util.LinkedHashMap<>();
                overrides.put("scanInterval", valStr);
                userService.updateStrategy(user.getUserId(), user.getStrategyType(), "", overrides);
                log.info("用户 {} 更新策略配置中的扫描间隔为 {} 分钟（交易未运行）", user.getUsername(), newInterval);
            }
        } catch (NumberFormatException e) {
            log.warn("无效的扫描间隔值: {}", valStr);
        }
        return redirect("/dashboard");
    }

    /** 超管操作：禁用/启用/停止交易 某用户 */
    private HttpResponse handleAdminPost(User admin, Map<String, String> p, Map<String, String> q) {
        String action = p.getOrDefault("action", q.getOrDefault("action", "")).trim();
        String targetId = p.getOrDefault("targetId", q.getOrDefault("targetId", "")).trim();

        User target = userService.getStore().findById(targetId);
        if (target == null) return redirect("/admin");

        // 超管账户不能被操作（保护自身也保护其他超管）
        if (target.isSuperAdmin()) {
            return buildPage(admin, pageAdmin(admin) + alertHtml("danger", "超管账户不可被操作"));
        }

        switch (action) {
            case "disable":
                target.setStatus(User.UserStatus.DISABLED);
                userService.getStore().save(target);
                break;
            case "enable":
                target.setStatus(User.UserStatus.ACTIVE);
                userService.getStore().save(target);
                break;
            case "stopTrader":
                AuctionTrader auctionT = auctionTraders.remove(target.getUserId());
                if (auctionT != null) auctionT.stopTrading();
                AutoTrader trader = userTraders.remove(target.getUserId());
                if (trader != null) trader.stopTrading();
                userService.setAutoTraderRunning(target.getUserId(), false);
                break;
        }
        return redirect("/admin");
    }

    private HttpResponse handleStart(User user) {
        // 已在运行（AutoTrader 或 AuctionTrader）
        if (userTraders.containsKey(user.getUserId())
                || auctionTraders.containsKey(user.getUserId())) {
            return redirect("/dashboard");
        }
        StrategyConfig sc = userService.getStrategyConfig(user.getUserId());
        String accountDir = userService.getStore().getUserAccountDir(user.getUserId());
        String reportDir = userService.getStore().getUserReportDir(user.getUserId());

        // ===== 竞价封板策略：启动专用 AuctionTrader =====
        if (User.StrategyType.AUCTION_LIMIT_UP.equals(user.getStrategyType())) {
            AuctionTrader auctionTrader = new AuctionTrader(
                    dataProvider,
                    user.getInitialCapital(),
                    user.getUserId(),
                    user.getUsername(),
                    accountDir,
                    reportDir,
                    sc,
                    user.getWechatOpenId(),
                    user.getWechatSendKey()
            );
            auctionTraders.put(user.getUserId(), auctionTrader);
            userService.setAutoTraderRunning(user.getUserId(), true);

            executor.submit(() -> {
                try {
                    auctionTrader.start();
                } catch (Exception e) {
                    log.error("用户 {} 竞价封板交易异常: {}", user.getUsername(), e.getMessage());
                } finally {
                    auctionTraders.remove(user.getUserId());
                    userService.setAutoTraderRunning(user.getUserId(), false);
                }
            });

            log.info("用户 {} 启动竞价封板策略交易", user.getUsername());
            return redirect("/dashboard");
        }

        // ===== 其他策略：启动标准 AutoTrader =====
        // 读取用户市场配置（HK=港股模式，CN/null=A股模式）
        String userMarket = user.getMarket() != null ? user.getMarket() : "CN";
        AutoTrader trader = new AutoTrader(
                dataProvider,
                user.getInitialCapital(),
                sc.getTopN() > 0 ? sc.getTopN() : 3,
                sc.getMinScore() > 0 ? sc.getMinScore() : 60,
                sc.getScanIntervalMin() > 0 ? sc.getScanIntervalMin() : 30,
                user.getUserId(),
                user.getUsername(),
                accountDir,
                reportDir,
                sc,
                user.getStrategyType(),
                user.getWechatOpenId(),
                user.getWechatSendKey(),
                userMarket
        );

        // ===== 实盘模式：如全局配置启用 live_trade，则注入 QMT 适配器 =====
        com.stocktrader.config.SystemConfig sysConf = com.stocktrader.config.SystemConfig.getInstance();
        if (sysConf.isLiveTradeEnabled()) {
            String bridgeUrl = sysConf.getLiveTradeBridgeUrl();
            com.stocktrader.trading.LiveBrokerAdapter liveAdapter =
                    new com.stocktrader.trading.LiveBrokerAdapter(new com.stocktrader.trading.FeeCalculator(), bridgeUrl);
            if (liveAdapter.isLiveAvailable()) {
                trader.setLiveBrokerAdapter(liveAdapter);
                log.info("用户 {} 已启用 QMT 实盘交易模式（bridge={}）", user.getUsername(), bridgeUrl);
            } else {
                log.warn("用户 {} live.trade.enabled=true，但 qmt_bridge 不可用（{}），降级为模拟模式",
                        user.getUsername(), bridgeUrl);
            }
        }

        userTraders.put(user.getUserId(), trader);
        userService.setAutoTraderRunning(user.getUserId(), true);

        // 后台线程启动（不阻塞）
        executor.submit(() -> {
            try {
                trader.start();
            } catch (Exception e) {
                log.error("用户 {} 自动交易异常: {}", user.getUsername(), e.getMessage());
            } finally {
                userTraders.remove(user.getUserId());
                userService.setAutoTraderRunning(user.getUserId(), false);
            }
        });

        log.info("用户 {} 启动自动交易", user.getUsername());
        return redirect("/dashboard");
    }

    private HttpResponse handleStop(User user) {
        // 停止竞价封板 Trader
        AuctionTrader auctionTrader = auctionTraders.remove(user.getUserId());
        if (auctionTrader != null) {
            auctionTrader.stopTrading();
        }
        // 停止标准 AutoTrader
        AutoTrader trader = userTraders.remove(user.getUserId());
        userService.setAutoTraderRunning(user.getUserId(), false);
        if (trader != null) {
            trader.stopTrading();
        }
        log.info("用户 {} 停止自动交易", user.getUsername());
        return redirect("/dashboard");
    }

    // =================== 页面生成 ===================

    private String pageLogin(String errMsg) {
        String err = errMsg.isEmpty() ? "" : alertHtml("danger", errMsg);
        return "<div class='card shadow' style='max-width:420px;margin:60px auto'>"
            + "<div class='card-body p-4'>"
            + "<h3 class='text-center mb-4'>📈 智能交易平台</h3>"
            + err
            + "<form method='POST' action='/login'>"
            + formGroup("用户名", "username", "text", "请输入用户名")
            + formGroup("密码", "password", "password", "请输入密码")
            + "<button class='btn btn-primary w-100 mt-3' type='submit'>登录</button>"
            + "</form>"
            + "<hr><p class='text-center mb-0'>还没有账号？<a href='/register'>立即注册</a></p>"
            + "</div></div>";
    }

    private String pageRegister() {
        return "<div class='card shadow' style='max-width:520px;margin:40px auto'>"
            + "<div class='card-body p-4'>"
            + "<h3 class='text-center mb-4'>📝 注册新账户</h3>"
            + "<form method='POST' action='/register'>"
            + formGroup("用户名", "username", "text", "4-20位字母/数字/下划线")
            + formGroup("密码", "password", "password", "6-30位")
            + formGroup("昵称（选填）", "nickname", "text", "显示名称")
            + formGroup("初始资金（元）", "capital", "number", "如：100000")
            + "<div class='mb-3'><label class='form-label'>默认策略</label>"
            + "<select name='strategy' class='form-select'>"
            + "<option value='DAY_TRADE'>📊 短线做T策略（高频，日内操作）</option>"
            + "<option value='MEDIUM_LONG'>📈 中长期策略（稳健，持仓5-20天）</option>"
            + "<option value='CUSTOM'>✏️ 自定义策略（稍后配置）</option>"
            + "</select><div class='form-text'>系统会根据您的资金量自动优化策略参数</div></div>"
            + "<button class='btn btn-success w-100 mt-3' type='submit'>注册并开始</button>"
            + "</form>"
            + "<hr><p class='text-center mb-0'>已有账号？<a href='/'>登录</a></p>"
            + "</div></div>";
    }

    private String pageDashboard(User user) {
        StrategyConfig sc = userService.getStrategyConfig(user.getUserId());
        AutoTrader trader = userTraders.get(user.getUserId());
        // 竞价封板策略使用 AuctionTrader，需同时检查
        boolean running = trader != null || auctionTraders.containsKey(user.getUserId());

        // 获取账户状态（accountId = userId + "_auto_main"，与 AutoTrader 多用户模式一致）
        String accountDir = userService.getStore().getUserAccountDir(user.getUserId());
        AccountPersistence ap = new AccountPersistence(accountDir);
        String acctId = user.getUserId() + "_auto_main";
        Portfolio portfolio = ap.load(acctId);
        if (portfolio == null) {
            portfolio = new Portfolio(acctId, user.getNickname() + "的账户",
                    user.getInitialCapital(), Portfolio.AccountMode.SIMULATION);
        }

        double totalAssets = portfolio.getTotalAssets();
        double profit = totalAssets - user.getInitialCapital();
        double profitRate = user.getInitialCapital() > 0 ? profit / user.getInitialCapital() * 100 : 0;
        double realizedPnl = portfolio.getRealizedPnl();
        double floatPnl = portfolio.getPositionProfit();
        double realizedPnlRate = user.getInitialCapital() > 0 ? realizedPnl / user.getInitialCapital() * 100 : 0;

        StringBuilder sb = new StringBuilder();

        // 顶部状态卡（2行：第1行总览，第2行已实现/浮动拆分）
        sb.append("<div class='row g-3 mb-2'>");
        sb.append(statCard("💰 总资产", String.format("%,.2f 元", totalAssets), "primary"));
        sb.append(statCard("💵 可用资金", String.format("%,.2f 元", portfolio.getAvailableCash()), "info"));
        sb.append(statCard(profit >= 0 ? "📈 总盈亏" : "📉 总亏损",
                String.format("%+,.2f 元 (%+.2f%%)", profit, profitRate),
                profit >= 0 ? "success" : "danger"));
        sb.append(statCard("🤖 交易状态", running ? "✅ 运行中" : "⏸️ 已停止", running ? "success" : "secondary"));
        sb.append("</div>");
        sb.append("<div class='row g-3 mb-4'>");
        sb.append(statCard("✅ 已实现盈亏",
                String.format("%+,.2f 元 (%+.2f%%)", realizedPnl, realizedPnlRate),
                realizedPnl >= 0 ? "success" : "danger"));
        sb.append(statCard("📊 持仓浮动盈亏",
                String.format("%+,.2f 元", floatPnl),
                floatPnl >= 0 ? "success" : "danger"));
        long closedCount = portfolio.getClosedPositions().size();
        long winCount = portfolio.getClosedPositions().stream().filter(c -> c.getRealizedPnl() > 0).count();
        String winRate = closedCount > 0 ? String.format("%.0f%%", winCount * 100.0 / closedCount) : "-";
        sb.append(statCard("🎯 胜率", closedCount > 0 ? winRate + " (" + winCount + "/" + closedCount + "笔)" : "暂无交易", "warning"));
        double avgPnlPerTrade = closedCount > 0 ? realizedPnl / closedCount : 0;
        sb.append(statCard("📉 均笔盈亏",
                closedCount > 0 ? String.format("%+,.2f 元/笔", avgPnlPerTrade) : "暂无交易",
                avgPnlPerTrade >= 0 ? "success" : "danger"));
        sb.append("</div>");

        // 超管标识
        if (user.isSuperAdmin()) {
            sb.append("<div class='alert alert-warning py-2 mb-3'>"
                    + "<b>👑 超级管理员</b> — 您的账户受保护，其他用户无法修改您的信息"
                    + "</div>");
        }

        // 控制按钮
        sb.append("<div class='mb-4'>");
        if (!running) {
            sb.append("<form method='POST' action='/start' class='d-inline'>"
                    + "<button class='btn btn-success me-2'>▶ 启动自动交易</button></form>");
        } else {
            sb.append("<form method='POST' action='/stop' class='d-inline'>"
                    + "<button class='btn btn-warning me-2' onclick=\"return confirm('确认停止？')\">⏹ 停止交易</button></form>");
        }
        sb.append("<a href='/strategy' class='btn btn-outline-primary me-2'>⚙️ 策略配置</a>");
        sb.append("<a href='/trades' class='btn btn-outline-info me-2'>📜 成交盈亏</a>");
        sb.append("<a href='/stats' class='btn btn-outline-warning me-2'>📅 收益统计</a>");
        sb.append("<a href='/rollback-records' class='btn btn-outline-danger me-2'>🔄 撤销记录</a>");
        if (user.isSuperAdmin()) {
            sb.append("<a href='/admin' class='btn btn-outline-danger me-2'>🛡️ 用户管理</a>");
        }
        sb.append("<button class='btn btn-outline-secondary' onclick='location.reload()'>🔄 刷新</button>");
        sb.append("</div>");

        // 策略信息 + 扫描间隔动态调节
        int currentInterval = (trader != null)
                ? trader.getScanIntervalMin()
                : (sc != null && sc.getScanIntervalMin() > 0 ? sc.getScanIntervalMin() : 30);
        sb.append("<div class='card mb-4'>");
        sb.append("<div class='card-header d-flex justify-content-between align-items-center'>");
        sb.append("<span>⚙️ 当前策略</span>");
        sb.append(String.format("<span class='badge bg-%s'>扫描间隔: %d 分钟</span>",
                running ? "success" : "secondary", currentInterval));
        sb.append("</div>");
        sb.append("<div class='card-body'>");
        sb.append(String.format("<span class='badge bg-primary fs-6'>%s</span> &nbsp;",
                user.getStrategyType().getDesc()));
        if (sc != null) sb.append("<small class='text-muted'>" + htmlEscape(sc.getSummary()) + "</small>");
        // 扫描间隔调节区域
        sb.append("<hr class='my-2'>");
        sb.append("<div class='d-flex align-items-center gap-2 flex-wrap'>");
        sb.append("<span class='text-muted small me-1'>⏱ 调整扫描频次：</span>");
        // 快捷档位按钮（仅运行中才直接生效，停止状态也可预设）
        int[] presets = {5, 10, 15, 30, 60};
        for (int p : presets) {
            String btnStyle = (p == currentInterval) ? "btn-success" : "btn-outline-secondary";
            sb.append(String.format(
                "<form method='POST' action='/scan-interval' class='d-inline'>"
                + "<input type='hidden' name='scanInterval' value='%d'>"
                + "<button type='submit' class='btn btn-sm %s me-1'>%d分</button></form>",
                p, btnStyle, p));
        }
        // 自定义输入
        sb.append("<form method='POST' action='/scan-interval' class='d-inline d-flex align-items-center gap-1'>");
        sb.append(String.format(
                "<input type='number' name='scanInterval' min='1' max='120' value='%d' "
                + "class='form-control form-control-sm' style='width:80px' placeholder='分钟'>",
                currentInterval));
        sb.append("<button type='submit' class='btn btn-sm btn-primary'>✓ 应用</button></form>");
        if (running) {
            sb.append("<small class='text-success ms-1'>✅ 运行中，立即生效</small>");
        } else {
            sb.append("<small class='text-muted ms-1'>（启动后生效）</small>");
        }
        sb.append("</div>");
        sb.append("</div></div>");

        // 持仓明细
        sb.append("<div class='card mb-4'><div class='card-header'>📋 当前持仓</div><div class='card-body'>");
        if (portfolio.getPositions().isEmpty()) {
            sb.append("<p class='text-muted mb-0'>当前空仓</p>");
        } else {
            sb.append("<div class='table-responsive'><table class='table table-hover mb-0'>");
            sb.append("<thead><tr><th>代码</th><th>名称</th><th>持仓</th><th>可用</th>"
                    + "<th>成本价</th><th>现价</th><th>市值</th><th>浮动盈亏</th><th>收益率</th></tr></thead><tbody>");
            portfolio.getPositions().forEach((code, pos) -> {
                double r = pos.getAvgCost() > 0 ? (pos.getCurrentPrice() - pos.getAvgCost()) / pos.getAvgCost() * 100 : 0;
                double pnlVal = (pos.getCurrentPrice() - pos.getAvgCost()) * pos.getQuantity();
                double mv = pos.getCurrentPrice() * pos.getQuantity();
                String color = r >= 0 ? "text-success" : "text-danger";
                sb.append(String.format(
                        "<tr><td><b>%s</b></td><td>%s</td><td>%d</td><td>%d</td>"
                        + "<td>%.2f</td><td>%.2f</td><td>%,.0f</td>"
                        + "<td class='%s'><b>%+,.2f</b></td><td class='%s'><b>%+.2f%%</b></td></tr>",
                        htmlEscape(code),
                        htmlEscape(pos.getStockName() != null ? pos.getStockName() : code),
                        pos.getQuantity(), pos.getAvailableQuantity(),
                        pos.getAvgCost(), pos.getCurrentPrice(), mv,
                        color, pnlVal, color, r));
            });
            sb.append("</tbody></table></div>");
        }
        sb.append("</div></div>");

        // 历史交易记录（已平仓）
        java.util.List<ClosedPosition> closed = portfolio.getClosedPositions();
        sb.append("<div class='card mb-4'>");
        sb.append("<div class='card-header d-flex justify-content-between align-items-center'>");
        sb.append("<span>📜 历史交易记录（已平仓 " + closed.size() + " 笔）</span>");
        if (!closed.isEmpty()) {
            sb.append("<span class='badge bg-" + (realizedPnl >= 0 ? "success" : "danger") + " fs-6'>"
                    + String.format("累计已实现盈亏 %+,.2f 元", realizedPnl) + "</span>");
        }
        sb.append("</div><div class='card-body'>");
        if (closed.isEmpty()) {
            sb.append("<p class='text-muted mb-0'>暂无已平仓记录</p>");
        } else {
            sb.append("<div class='table-responsive'><table class='table table-sm table-hover mb-0'>");
            sb.append("<thead class='table-light'><tr>"
                    + "<th>代码</th><th>名称</th><th>数量</th>"
                    + "<th>买入均价</th><th>卖出价</th><th>手续费</th>"
                    + "<th>已实现盈亏</th><th>收益率</th><th>平仓原因</th><th>卖出时间</th>"
                    + "</tr></thead><tbody>");
            // 倒序展示（最新的在最前）
            java.util.List<ClosedPosition> reversedClosed = new java.util.ArrayList<>(closed);
            java.util.Collections.reverse(reversedClosed);
            for (ClosedPosition cp : reversedClosed) {
                String pnlColor = cp.getRealizedPnl() >= 0 ? "text-success" : "text-danger";
                String reasonShort = cp.getCloseReason() != null
                        ? (cp.getCloseReason().length() > 30
                                ? cp.getCloseReason().substring(0, 30) + "…"
                                : cp.getCloseReason())
                        : "-";
                String sellTimeStr = cp.getSellTime() != null ? cp.getSellTime().format(DT_FMT) : "-";
                sb.append(String.format(
                        "<tr><td><b>%s</b></td><td>%s</td><td>%d</td>"
                        + "<td>%.2f</td><td>%.2f</td><td>%.2f</td>"
                        + "<td class='%s'><b>%+,.2f</b></td>"
                        + "<td class='%s'><b>%+.2f%%</b></td>"
                        + "<td><small class='text-muted' title='%s'>%s</small></td>"
                        + "<td><small>%s</small></td></tr>",
                        htmlEscape(cp.getStockCode()),
                        htmlEscape(cp.getStockName() != null ? cp.getStockName() : cp.getStockCode()),
                        cp.getQuantity(),
                        cp.getAvgCost(), cp.getSellPrice(), cp.getTotalFee(),
                        pnlColor, cp.getRealizedPnl(),
                        pnlColor, cp.getRealizedPnlRate(),
                        htmlEscape(cp.getCloseReason() != null ? cp.getCloseReason() : ""),
                        htmlEscape(reasonShort),
                        htmlEscape(sellTimeStr)));
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div></div>");

        // 微信配置
        String openId  = user.getWechatOpenId()  != null ? user.getWechatOpenId()  : "";
        String sendKey = user.getWechatSendKey() != null ? user.getWechatSendKey() : "";
        boolean mpConfigured = !openId.isEmpty();
        boolean scConfigured = !sendKey.isEmpty();
        sb.append("<div class='card mb-4'><div class='card-header'>📱 微信推送配置</div><div class='card-body'>");
        sb.append("<form method='POST' action='/wechat'>");
        // openid 输入
        sb.append("<div class='mb-3'>");
        sb.append("<label class='form-label fw-bold'>🎯 公众号 openid（主通道，推荐）</label>");
        sb.append("<input type='text' name='openId' class='form-control' "
                + "placeholder='关注公众号后，在后台「用户管理」或测试号页面获取 openid' "
                + "value='" + htmlEscape(openId) + "'>");
        if (mpConfigured) {
            sb.append("<small class='text-success'>✅ 已配置 openid，公众号模板消息推送已启用</small>");
        } else {
            sb.append("<small class='text-muted'>配置后将通过公众号模板消息推送交易信号，无条数限制。"
                    + "测试号申请：<a href='https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo' target='_blank'>mp.weixin.qq.com/debug</a></small>");
        }
        sb.append("</div>");
        // Server酱 Key 输入（降级）
        sb.append("<div class='mb-3'>");
        sb.append("<label class='form-label fw-bold'>🔁 Server酱 SendKey（备用降级通道）</label>");
        sb.append("<input type='text' name='sendKey' class='form-control' "
                + "placeholder='SCTxxxxxxx（公众号未配置时自动使用此通道）' "
                + "value='" + htmlEscape(sendKey) + "'>");
        if (scConfigured) {
            sb.append("<small class='text-success'>✅ 已配置 SendKey（每日5条免费，作降级备用）</small>");
        } else {
            sb.append("<small class='text-muted'>访问 <a href='https://sct.ftqq.com' target='_blank'>sct.ftqq.com</a> 获取免费SendKey</small>");
        }
        sb.append("</div>");
        sb.append("<button class='btn btn-outline-success'>保存推送配置</button>");
        sb.append("</form>");
        sb.append("</div></div>");

        return sb.toString();
    }

    private String pageStrategy(User user) {
        StrategyConfig sc = userService.getStrategyConfig(user.getUserId());
        if (sc == null) sc = StrategyConfig.buildDayTrade(user.getInitialCapital());

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='card shadow'><div class='card-header'><h5 class='mb-0'>⚙️ 策略配置</h5></div><div class='card-body'>");
        sb.append("<form method='POST' action='/strategy'>");

        // 策略类型选择
        sb.append("<div class='mb-4'><label class='form-label fw-bold'>策略类型</label>");
        sb.append("<div class='row g-3'>");
        String[] stTypes = {"DAY_TRADE", "MEDIUM_LONG", "SWAP_STRONG", "CUSTOM"};
        String[] stLabels = {"📊 短线做T策略", "📈 中长期策略", "🔄 换股策略(汰弱留强)", "✏️ 自定义策略"};
        String[] stDescs = {
            "高频操作，日内做T，止损严格（3%-5%），扫描间隔5-15分钟，适合活跃型投资者",
            "稳健持仓，持股5-20天，止损宽松（6%-8%），关注均线趋势，适合稳健型投资者",
            "满仓后定期扫描大盘，发现评分更高的标的则换出最弱持仓，实现动态汰弱留强，适合中短期投资者",
            "自定义描述策略，系统根据你的描述生成参数，也可手动调整每个参数"
        };
        for (int i = 0; i < stTypes.length; i++) {
            boolean selected = user.getStrategyType() != null && user.getStrategyType().name().equals(stTypes[i]);
            sb.append(String.format(
                "<div class='col-md-4'><div class='card h-100 %s' style='cursor:pointer' onclick='selectStrategy(\"%s\")'>"
                + "<div class='card-body'><div class='form-check'>"
                + "<input class='form-check-input' type='radio' name='strategyType' value='%s' id='st%s' %s>"
                + "<label class='form-check-label fw-bold' for='st%s'>%s</label>"
                + "</div><small class='text-muted'>%s</small></div></div></div>",
                selected ? "border-primary" : "", stTypes[i],
                stTypes[i], stTypes[i], selected ? "checked" : "",
                stTypes[i], stLabels[i], stDescs[i]
            ));
        }
        sb.append("</div></div>");

        // 自定义描述（CUSTOM时可见）
        String customDesc = sc.getCustomDescription() != null ? sc.getCustomDescription() : "";
        sb.append("<div class='mb-3' id='customDescDiv' style='display:" +
                (user.getStrategyType() == User.StrategyType.CUSTOM ? "block" : "none") + "'>");
        sb.append("<label class='form-label fw-bold'>📝 用自己的话描述你的交易策略</label>");
        sb.append("<textarea name='customDesc' class='form-control' rows='3' placeholder='例如：我想做短线，偏激进风格，重仓2只，出现MACD死叉就卖，止损5%'>"
                + htmlEscape(customDesc) + "</textarea>");
        sb.append("<div class='form-text'>系统会分析你的描述，自动生成对应的策略参数</div></div>");

        // 参数手动微调
        sb.append("<hr><h6 class='mb-3'>🔧 参数微调（留空则使用自动值）</h6>");
        sb.append("<div class='row g-3'>");
        sb.append(paramInput("止损比例 %", "stopLoss", String.format("%.0f", sc.getStopLossPercent() * 100), "如：5（表示5%）"));
        sb.append(paramInput("减仓止盈 %", "takeProfitHalf", String.format("%.0f", sc.getTakeProfitHalfPercent() * 100), "如：10"));
        sb.append(paramInput("清仓止盈 %", "takeProfitFull", String.format("%.0f", sc.getTakeProfitFullPercent() * 100), "如：20"));
        sb.append(paramInput("单只最大仓位 %", "maxPosition", String.format("%.0f", sc.getMaxPositionRatio() * 100), "如：35"));
        sb.append(paramInput("最多持股只数", "maxPositions", String.valueOf(sc.getMaxPositions()), "如：3"));
        sb.append(paramInput("选股Top N", "topN", String.valueOf(sc.getTopN()), "如：3"));
        sb.append(paramInput("最低评分 (0-100)", "minScore", String.valueOf(sc.getMinScore()), "如：60"));
        sb.append(paramInput("扫描间隔（分钟）", "scanInterval", String.valueOf(sc.getScanIntervalMin()), "如：30"));
        sb.append("</div>");

        // 资金说明
        sb.append(String.format("<div class='alert alert-info mt-3'>"
                + "<b>💡 当前资金量：%,.0f 元</b> — 系统已根据资金量自动优化了默认参数，您可按需调整。"
                + "</div>", user.getInitialCapital()));

        sb.append("<button class='btn btn-primary mt-3' type='submit'>💾 保存策略配置</button>");
        sb.append("<a href='/dashboard' class='btn btn-secondary mt-3 ms-2'>返回</a>");
        sb.append("</form></div></div>");

        sb.append("<script>function selectStrategy(v){"
                + "document.querySelectorAll('[name=strategyType]').forEach(r=>r.checked=r.value===v);"
                + "document.getElementById('customDescDiv').style.display=v==='CUSTOM'?'block':'none';"
                + "document.querySelectorAll('.card.border-primary').forEach(c=>c.classList.remove('border-primary'));"
                + "document.getElementById('st'+v).closest('.card').classList.add('border-primary');}</script>");
        return sb.toString();
    }

    /** 超级管理员用户管理页 */
    private String pageAdmin(User admin) {
        java.util.List<User> allUsers = userService.getStore().findAll();
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='d-flex justify-content-between align-items-center mb-3'>");
        sb.append("<h5 class='mb-0'>🛡️ 用户管理</h5>");
        sb.append("<a href='/dashboard' class='btn btn-sm btn-outline-secondary'>← 返回控制台</a>");
        sb.append("</div>");
        sb.append("<div class='card'><div class='card-body p-0'>");
        sb.append("<div class='table-responsive'><table class='table table-hover mb-0'>");
        sb.append("<thead class='table-dark'><tr>"
                + "<th>用户名</th><th>昵称</th><th>资金(元)</th><th>策略</th>"
                + "<th>状态</th><th>交易中</th><th>注册时间</th><th>操作</th></tr></thead><tbody>");
        for (User u : allUsers) {
            // 需同时检查 AutoTrader 和 AuctionTrader（竞价封板策略）
            boolean isRunning = userTraders.containsKey(u.getUserId()) || auctionTraders.containsKey(u.getUserId());
            String statusBadge = u.getStatus() == User.UserStatus.ACTIVE
                    ? "<span class='badge bg-success'>正常</span>"
                    : "<span class='badge bg-secondary'>禁用</span>";
            String adminBadge = u.isSuperAdmin() ? " <span class='badge bg-warning text-dark'>👑超管</span>" : "";
            String runBadge = isRunning ? "<span class='badge bg-primary'>运行中</span>" : "<span class='badge bg-light text-muted'>停止</span>";
            String createTime = u.getCreateTime() != null ? u.getCreateTime().format(DT_FMT) : "-";
            String strategyName = u.getStrategyType() != null ? u.getStrategyType().getDesc() : "-";

            // 操作按钮（超管账户不可操作）
            String ops;
            if (u.isSuperAdmin()) {
                ops = "<span class='text-muted small'>受保护</span>";
            } else {
                ops = "<div class='d-flex gap-1 flex-wrap'>";
                if (u.getStatus() == User.UserStatus.ACTIVE) {
                    ops += String.format("<form method='POST' action='/admin' class='d-inline'>"
                            + "<input type='hidden' name='action' value='disable'>"
                            + "<input type='hidden' name='targetId' value='%s'>"
                            + "<button class='btn btn-sm btn-outline-warning' onclick=\"return confirm('确认禁用？')\">禁用</button></form>", u.getUserId());
                } else {
                    ops += String.format("<form method='POST' action='/admin' class='d-inline'>"
                            + "<input type='hidden' name='action' value='enable'>"
                            + "<input type='hidden' name='targetId' value='%s'>"
                            + "<button class='btn btn-sm btn-outline-success'>启用</button></form>", u.getUserId());
                }
                if (isRunning) {
                    ops += String.format("<form method='POST' action='/admin' class='d-inline'>"
                            + "<input type='hidden' name='action' value='stopTrader'>"
                            + "<input type='hidden' name='targetId' value='%s'>"
                            + "<button class='btn btn-sm btn-outline-danger' onclick=\"return confirm('强制停止该用户交易？')\">停止交易</button></form>", u.getUserId());
                }
                ops += "</div>";
            }

            sb.append(String.format(
                "<tr><td><b>%s</b>%s</td><td>%s</td><td>%,.0f</td><td><small>%s</small></td>"
                + "<td>%s</td><td>%s</td><td><small>%s</small></td><td>%s</td></tr>",
                htmlEscape(u.getUsername()), adminBadge,
                htmlEscape(u.getNickname() != null ? u.getNickname() : ""),
                u.getInitialCapital(), htmlEscape(strategyName),
                statusBadge, runBadge, htmlEscape(createTime), ops));
        }
        sb.append("</tbody></table></div></div></div>");
        sb.append("<div class='mt-3 text-muted small'>共 ").append(allUsers.size()).append(" 个用户</div>");
        return sb.toString();
    }

    // =================== 成交盈亏分析页 ===================

    /**
     * 【需求3】历史成交盈亏分析页：展示每笔交易的买入/卖出/盈亏/持仓天数
     * 按股票代码聚合，同一支股票的多次买卖合并为一行，显示完整盈亏。
     * 直接从数据库读取，不依赖内存 portfolio，重启后数据不丢失。
     */
    private String pageTrades(com.stocktrader.model.User user) {
        String acctId = user.getUserId() + "_auto_main";
        List<com.stocktrader.model.ClosedPosition> closed = loadClosedPositionsFromDb(acctId);
        double totalPnl = closed.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnl).sum();
        long totalTrades = closed.size();
        long winTrades = closed.stream().filter(c -> c.getRealizedPnl() > 0).count();
        double maxWin = closed.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnl).max().orElse(0);
        double maxLoss = closed.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnl).min().orElse(0);
        double avgPnl = totalTrades > 0 ? totalPnl / totalTrades : 0;
        double winRate = totalTrades > 0 ? winTrades * 100.0 / totalTrades : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='d-flex justify-content-between align-items-center mb-3'>");
        sb.append("<h5 class='mb-0'>📜 历史成交盈亏分析</h5>");
        sb.append("<a href='/dashboard' class='btn btn-sm btn-outline-secondary'>← 返回控制台</a>");
        sb.append("</div>");

        // 汇总统计卡片
        sb.append("<div class='row g-3 mb-4'>");
        sb.append(statCard("📊 总交易笔数", totalTrades + " 笔", "primary"));
        sb.append(statCard("🎯 胜率", totalTrades > 0 ? String.format("%.1f%% (%d/%d)", winRate, winTrades, totalTrades) : "-", "info"));
        sb.append(statCard("💰 累计已实现盈亏", String.format("%+,.2f 元", totalPnl), totalPnl >= 0 ? "success" : "danger"));
        sb.append(statCard("📈 均笔盈亏", totalTrades > 0 ? String.format("%+,.2f 元/笔", avgPnl) : "-", avgPnl >= 0 ? "success" : "danger"));
        sb.append(statCard("🏆 最大单笔盈利", String.format("%+,.2f 元", maxWin), "success"));
        sb.append(statCard("⚠️ 最大单笔亏损", String.format("%+,.2f 元", maxLoss), "danger"));
        sb.append("</div>");

        // ===== 按股票代码聚合统计 =====
        Map<String, List<com.stocktrader.model.ClosedPosition>> byCode = new LinkedHashMap<>();
        // 倒序（最新在前）
        List<com.stocktrader.model.ClosedPosition> reversedAll = new ArrayList<>(closed);
        Collections.reverse(reversedAll);
        for (com.stocktrader.model.ClosedPosition cp : reversedAll) {
            byCode.computeIfAbsent(cp.getStockCode(), k -> new ArrayList<>()).add(cp);
        }

        sb.append("<div class='card mb-4'><div class='card-header fw-bold'>📋 按股票汇总盈亏（共 ")
          .append(byCode.size()).append(" 只）</div><div class='card-body p-0'>");
        sb.append("<div class='table-responsive'><table class='table table-sm table-hover mb-0'>");
        sb.append("<thead class='table-dark'><tr>"
                + "<th>代码</th><th>名称</th><th>交易次数</th><th>累计买入额</th>"
                + "<th>累计卖出额</th><th>累计手续费</th>"
                + "<th>累计盈亏</th><th>盈亏率</th><th>最近操作</th></tr></thead><tbody>");
        for (Map.Entry<String, List<com.stocktrader.model.ClosedPosition>> e : byCode.entrySet()) {
            List<com.stocktrader.model.ClosedPosition> list = e.getValue();
            String code = e.getKey();
            String name = list.get(0).getStockName() != null ? list.get(0).getStockName() : code;
            int tradeCount = list.size();
            double buyAmt  = list.stream().mapToDouble(c -> c.getAvgCost() * c.getQuantity()).sum();
            double sellAmt = list.stream().mapToDouble(c -> c.getSellPrice() * c.getQuantity()).sum();
            double fee     = list.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getTotalFee).sum();
            double pnl     = list.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnl).sum();
            double pnlRate = buyAmt > 0 ? pnl / buyAmt * 100 : 0;
            String lastTime = list.get(0).getSellTime() != null ? list.get(0).getSellTime().format(DT_FMT) : "-";
            String clr = pnl >= 0 ? "text-success" : "text-danger";
            sb.append(String.format(
                "<tr><td><b>%s</b></td><td>%s</td><td>%d</td>"
                + "<td>%,.0f</td><td>%,.0f</td><td>%,.2f</td>"
                + "<td class='%s fw-bold'>%+,.2f</td>"
                + "<td class='%s fw-bold'>%+.2f%%</td>"
                + "<td><small>%s</small></td></tr>",
                htmlEscape(code), htmlEscape(name), tradeCount,
                buyAmt, sellAmt, fee,
                clr, pnl, clr, pnlRate,
                htmlEscape(lastTime)));
        }
        sb.append("</tbody></table></div></div></div>");

        // ===== 明细列表 =====
        sb.append("<div class='card'><div class='card-header fw-bold'>🗒️ 全部成交明细（共 ")
          .append(totalTrades).append(" 笔）</div><div class='card-body p-0'>");
        if (closed.isEmpty()) {
            sb.append("<p class='text-muted p-3 mb-0'>暂无历史交易记录</p>");
        } else {
            sb.append("<div class='table-responsive'><table class='table table-sm table-striped mb-0'>");
            sb.append("<thead class='table-light'><tr>"
                    + "<th>#</th><th>代码</th><th>名称</th><th>数量</th>"
                    + "<th>买入均价</th><th>卖出价</th><th>手续费</th>"
                    + "<th>盈亏(元)</th><th>盈亏率</th><th>持仓天数</th>"
                    + "<th>买入时间</th><th>卖出时间</th><th>原因</th></tr></thead><tbody>");
            int idx = reversedAll.size();
            for (com.stocktrader.model.ClosedPosition cp : reversedAll) {
                String clr = cp.getRealizedPnl() >= 0 ? "text-success" : "text-danger";
                String buyTs = cp.getBuyTime() != null ? cp.getBuyTime().format(DT_FMT) : "-";
                String sellTs = cp.getSellTime() != null ? cp.getSellTime().format(DT_FMT) : "-";
                long holdDays = 0;
                if (cp.getBuyTime() != null && cp.getSellTime() != null) {
                    holdDays = java.time.temporal.ChronoUnit.DAYS.between(
                            cp.getBuyTime().toLocalDate(), cp.getSellTime().toLocalDate());
                }
                String reasonShort = cp.getCloseReason() != null
                        ? (cp.getCloseReason().length() > 25
                                ? cp.getCloseReason().substring(0, 25) + "…"
                                : cp.getCloseReason())
                        : "-";
                sb.append(String.format(
                    "<tr><td class='text-muted'>%d</td>"
                    + "<td><b>%s</b></td><td>%s</td><td>%d</td>"
                    + "<td>%.2f</td><td>%.2f</td><td>%.2f</td>"
                    + "<td class='%s fw-bold'>%+,.2f</td>"
                    + "<td class='%s fw-bold'>%+.2f%%</td>"
                    + "<td>%d天</td>"
                    + "<td><small>%s</small></td><td><small>%s</small></td>"
                    + "<td><small class='text-muted' title='%s'>%s</small></td></tr>",
                    idx--,
                    htmlEscape(cp.getStockCode()),
                    htmlEscape(cp.getStockName() != null ? cp.getStockName() : cp.getStockCode()),
                    cp.getQuantity(),
                    cp.getAvgCost(), cp.getSellPrice(), cp.getTotalFee(),
                    clr, cp.getRealizedPnl(),
                    clr, cp.getRealizedPnlRate(),
                    holdDays,
                    htmlEscape(buyTs), htmlEscape(sellTs),
                    htmlEscape(cp.getCloseReason() != null ? cp.getCloseReason() : ""),
                    htmlEscape(reasonShort)));
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    // =================== 每日/每月收益统计页 ===================

    /**
     * 【需求4】按日/月汇总收益统计页
     * 直接从数据库读取，不依赖内存 portfolio，重启后数据不丢失。
     */
    private String pageStats(com.stocktrader.model.User user) {
        String acctId = user.getUserId() + "_auto_main";
        List<com.stocktrader.model.ClosedPosition> closed = loadClosedPositionsFromDb(acctId);

        // 按卖出日期分组统计
        // key = "yyyy-MM-dd", value = 当日已实现盈亏
        Map<String, Double> dailyPnl = new LinkedHashMap<>();
        Map<String, Integer> dailyCount = new LinkedHashMap<>();
        Map<String, Integer> dailyWin = new LinkedHashMap<>();
        DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        // 按时间升序
        List<com.stocktrader.model.ClosedPosition> sorted = closed.stream()
                .filter(c -> c.getSellTime() != null)
                .sorted(Comparator.comparing(com.stocktrader.model.ClosedPosition::getSellTime))
                .collect(Collectors.toList());

        for (com.stocktrader.model.ClosedPosition cp : sorted) {
            String day = cp.getSellTime().format(dateFmt);
            dailyPnl.merge(day, cp.getRealizedPnl(), Double::sum);
            dailyCount.merge(day, 1, Integer::sum);
            if (cp.getRealizedPnl() > 0) dailyWin.merge(day, 1, Integer::sum);
        }

        // 月度汇总
        Map<String, Double> monthlyPnl = new LinkedHashMap<>();
        Map<String, Integer> monthlyCount = new LinkedHashMap<>();
        Map<String, Integer> monthlyWin = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : dailyPnl.entrySet()) {
            String month = e.getKey().substring(0, 7); // yyyy-MM
            monthlyPnl.merge(month, e.getValue(), Double::sum);
            monthlyCount.merge(month, dailyCount.getOrDefault(e.getKey(), 0), Integer::sum);
            monthlyWin.merge(month, dailyWin.getOrDefault(e.getKey(), 0), Integer::sum);
        }

        // 累计盈亏（用于折线图数据）
        double cumPnl = 0;

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='d-flex justify-content-between align-items-center mb-3'>");
        sb.append("<h5 class='mb-0'>📅 收益统计</h5>");
        sb.append("<a href='/dashboard' class='btn btn-sm btn-outline-secondary'>← 返回控制台</a>");
        sb.append("</div>");

        // 顶部汇总
        double totalPnl = closed.stream().mapToDouble(com.stocktrader.model.ClosedPosition::getRealizedPnl).sum();
        long tradingDays = dailyPnl.size();
        long profitDays  = dailyPnl.values().stream().filter(v -> v > 0).count();
        sb.append("<div class='row g-3 mb-4'>");
        sb.append(statCard("💰 累计已实现盈亏", String.format("%+,.2f 元", totalPnl), totalPnl >= 0 ? "success" : "danger"));
        sb.append(statCard("📆 有交易天数", tradingDays + " 天", "primary"));
        sb.append(statCard("☀️ 盈利天数", profitDays + " 天", "success"));
        sb.append(statCard("🌧️ 亏损天数", (tradingDays - profitDays) + " 天", "danger"));
        sb.append("</div>");

        // ===== 月度统计表 =====
        sb.append("<div class='card mb-4'><div class='card-header fw-bold'>📅 月度收益统计</div>");
        sb.append("<div class='card-body p-0'>");
        if (monthlyPnl.isEmpty()) {
            sb.append("<p class='text-muted p-3 mb-0'>暂无交易数据</p>");
        } else {
            sb.append("<div class='table-responsive'><table class='table table-hover mb-0'>");
            sb.append("<thead class='table-dark'><tr>"
                    + "<th>月份</th><th>交易笔数</th><th>盈利笔</th><th>月胜率</th>"
                    + "<th>月度盈亏</th><th>盈亏率</th><th>状态</th></tr></thead><tbody>");
            // 倒序展示（最近月份在最前）
            List<String> months = new ArrayList<>(monthlyPnl.keySet());
            Collections.reverse(months);
            for (String month : months) {
                double pnl = monthlyPnl.getOrDefault(month, 0.0);
                int cnt   = monthlyCount.getOrDefault(month, 0);
                int win   = monthlyWin.getOrDefault(month, 0);
                double wr = cnt > 0 ? win * 100.0 / cnt : 0;
                double pnlRate = user.getInitialCapital() > 0 ? pnl / user.getInitialCapital() * 100 : 0;
                String clr = pnl >= 0 ? "text-success" : "text-danger";
                String badge = pnl >= 0
                        ? "<span class='badge bg-success'>盈利月</span>"
                        : "<span class='badge bg-danger'>亏损月</span>";
                sb.append(String.format(
                    "<tr><td><b>%s</b></td><td>%d</td><td>%d</td>"
                    + "<td>%.1f%%</td>"
                    + "<td class='%s fw-bold'>%+,.2f 元</td>"
                    + "<td class='%s'>%+.2f%%</td>"
                    + "<td>%s</td></tr>",
                    htmlEscape(month), cnt, win, wr,
                    clr, pnl,
                    clr, pnlRate,
                    badge));
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div></div>");

        // ===== 每日明细表（含走势图） =====
        // 先计算累计盈亏（升序）用于图表数据
        List<String> sortedDays = new ArrayList<>(dailyPnl.keySet()); // 已升序
        Map<String, Double> cumMap = new LinkedHashMap<>();
        double runningCum = 0;
        for (String day : sortedDays) {
            runningCum += dailyPnl.getOrDefault(day, 0.0);
            cumMap.put(day, runningCum);
        }

        sb.append("<div class='card'>");
        sb.append("<div class='card-header fw-bold'>📆 每日收益明细</div>");
        sb.append("<div class='card-body'>");

        if (dailyPnl.isEmpty()) {
            sb.append("<p class='text-muted mb-0'>暂无交易数据</p>");
        } else {
            // ===== 走势图区域 =====
            // 构建图表 JSON 数据（升序日期）
            StringBuilder chartLabels = new StringBuilder("[");
            StringBuilder chartDailyData = new StringBuilder("[");
            StringBuilder chartCumData = new StringBuilder("[");
            StringBuilder chartDailyColors = new StringBuilder("[");
            boolean firstItem = true;
            for (String day : sortedDays) {
                // 白名单校验：日期必须是 yyyy-MM-dd 格式，防止 XSS 注入
                if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) continue;
                if (!firstItem) {
                    chartLabels.append(",");
                    chartDailyData.append(",");
                    chartCumData.append(",");
                    chartDailyColors.append(",");
                }
                double dpnl = dailyPnl.getOrDefault(day, 0.0);
                double cpnl = cumMap.getOrDefault(day, 0.0);
                chartLabels.append("'").append(day).append("'");
                chartDailyData.append(String.format("%.2f", dpnl));
                chartCumData.append(String.format("%.2f", cpnl));
                chartDailyColors.append(dpnl >= 0 ? "'rgba(40,167,69,0.75)'" : "'rgba(220,53,69,0.75)'");
                firstItem = false;
            }
            chartLabels.append("]");
            chartDailyData.append("]");
            chartCumData.append("]");
            chartDailyColors.append("]");

            sb.append("<div class='mb-4'>");
            sb.append("<div class='d-flex justify-content-between align-items-center mb-2'>");
            sb.append("<span class='fw-bold text-secondary small'>📈 收益走势图</span>");
            sb.append("<div class='btn-group btn-group-sm' role='group'>");
            sb.append("<button type='button' class='btn btn-outline-primary active' id='btnShowBoth' onclick='showChart(\"both\")'>综合视图</button>");
            sb.append("<button type='button' class='btn btn-outline-success' id='btnShowCum' onclick='showChart(\"cum\")'>累计盈亏</button>");
            sb.append("<button type='button' class='btn btn-outline-danger' id='btnShowDaily' onclick='showChart(\"daily\")'>每日盈亏</button>");
            sb.append("</div></div>");
            sb.append("<div style='position:relative;height:280px;background:#fff;border:1px solid #dee2e6;border-radius:8px;padding:12px'>");
            sb.append("<canvas id='profitChart'></canvas>");
            sb.append("</div></div>");

            // ===== 每日明细表格 =====
            sb.append("<div class='table-responsive'><table class='table table-sm table-hover mb-0'>");
            sb.append("<thead class='table-light'><tr>"
                    + "<th>日期</th><th>交易笔数</th><th>盈利笔</th><th>日胜率</th>"
                    + "<th>当日盈亏</th><th>盈亏率</th><th>累计盈亏</th></tr></thead><tbody>");
            // 倒序展示（最新在前）
            List<String> days = new ArrayList<>(sortedDays);
            Collections.reverse(days);
            for (String day : days) {
                double pnl = dailyPnl.getOrDefault(day, 0.0);
                int cnt   = dailyCount.getOrDefault(day, 0);
                int win   = dailyWin.getOrDefault(day, 0);
                double wr = cnt > 0 ? win * 100.0 / cnt : 0;
                double pnlRate = user.getInitialCapital() > 0 ? pnl / user.getInitialCapital() * 100 : 0;
                double cum = cumMap.getOrDefault(day, 0.0);
                String clr = pnl >= 0 ? "text-success" : "text-danger";
                String cumClr = cum >= 0 ? "text-success" : "text-danger";
                sb.append(String.format(
                    "<tr><td><b>%s</b></td><td>%d</td><td>%d</td><td>%.1f%%</td>"
                    + "<td class='%s fw-bold'>%+,.2f 元</td>"
                    + "<td class='%s'>%+.2f%%</td>"
                    + "<td class='%s'>%+,.2f 元</td></tr>",
                    htmlEscape(day), cnt, win, wr,
                    clr, pnl,
                    clr, pnlRate,
                    cumClr, cum));
            }
            sb.append("</tbody></table></div>");

            // ===== Chart.js 脚本 =====
            sb.append("<script src='https://cdn.jsdelivr.net/npm/chart.js@4.4.0/dist/chart.umd.min.js'></script>");
            sb.append("<script>");
            sb.append("(function(){");
            sb.append("var labels=").append(chartLabels).append(";");
            sb.append("var dailyData=").append(chartDailyData).append(";");
            sb.append("var cumData=").append(chartCumData).append(";");
            sb.append("var dailyColors=").append(chartDailyColors).append(";");
            sb.append("var ctx=document.getElementById('profitChart').getContext('2d');");
            sb.append("var chart=new Chart(ctx,{");
            sb.append("  type:'bar',");
            sb.append("  data:{");
            sb.append("    labels:labels,");
            sb.append("    datasets:[");
            sb.append("      {type:'line',label:'累计盈亏(元)',data:cumData,");
            sb.append("       borderColor:'#0d6efd',backgroundColor:'rgba(13,110,253,0.08)',");
            sb.append("       borderWidth:2,pointRadius:3,pointHoverRadius:5,");
            sb.append("       tension:0.3,fill:true,yAxisID:'yCum',order:1},");
            sb.append("      {type:'bar',label:'当日盈亏(元)',data:dailyData,");
            sb.append("       backgroundColor:dailyColors,");
            sb.append("       borderRadius:3,yAxisID:'yDaily',order:2}");
            sb.append("    ]");
            sb.append("  },");
            sb.append("  options:{");
            sb.append("    responsive:true,maintainAspectRatio:false,");
            sb.append("    interaction:{mode:'index',intersect:false},");
            sb.append("    plugins:{");
            sb.append("      legend:{position:'top',labels:{font:{size:12}}},");
            sb.append("      tooltip:{callbacks:{label:function(ctx){");
            sb.append("        return ctx.dataset.label+': '+(ctx.raw>=0?'+':'')+ctx.raw.toFixed(2)+'元';");
            sb.append("      }}}");
            sb.append("    },");
            sb.append("    scales:{");
            sb.append("      x:{ticks:{maxTicksLimit:15,font:{size:11}},grid:{display:false}},");
            sb.append("      yCum:{type:'linear',position:'left',");
            sb.append("            title:{display:true,text:'累计盈亏(元)',font:{size:11}},");
            sb.append("            grid:{color:'rgba(0,0,0,0.05)'},");
            sb.append("            ticks:{callback:function(v){return (v>=0?'+':'')+v.toFixed(0)+'元';},font:{size:11}}},");
            sb.append("      yDaily:{type:'linear',position:'right',");
            sb.append("              title:{display:true,text:'当日盈亏(元)',font:{size:11}},");
            sb.append("              grid:{display:false},");
            sb.append("              ticks:{callback:function(v){return (v>=0?'+':'')+v.toFixed(0)+'元';},font:{size:11}}}");
            sb.append("    }");
            sb.append("  }");
            sb.append("});");
            // 切换视图函数
            sb.append("window.showChart=function(mode){");
            sb.append("  var ds=chart.data.datasets;");
            sb.append("  if(mode==='cum'){ds[0].hidden=false;ds[1].hidden=true;}");
            sb.append("  else if(mode==='daily'){ds[0].hidden=true;ds[1].hidden=false;}");
            sb.append("  else{ds[0].hidden=false;ds[1].hidden=false;}");
            sb.append("  chart.update();");
            sb.append("  document.querySelectorAll('#btnShowBoth,#btnShowCum,#btnShowDaily').forEach(function(b){b.classList.remove('active');});");
            sb.append("  document.getElementById(mode==='cum'?'btnShowCum':mode==='daily'?'btnShowDaily':'btnShowBoth').classList.add('active');");
            sb.append("};");
            sb.append("})();");
            sb.append("</script>");
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    // =================== DB 辅助查询 ===================

    /**
     * 直接从数据库查询已平仓记录，不依赖内存 portfolio。
     * 保证重启后历史数据仍可正常展示。
     */
    private List<com.stocktrader.model.ClosedPosition> loadClosedPositionsFromDb(String acctId) {
        List<com.stocktrader.model.ClosedPosition> result = new ArrayList<>();
        // [P0-1 优化] 使用独立短连接
        java.sql.Connection conn = null;
        try {
            conn = com.stocktrader.config.DatabaseManager.getInstance().newConnection();
            String sql = "SELECT stock_code, stock_name, quantity, avg_cost, sell_price, " +
                    "total_fee, realized_pnl, realized_pnl_rate, close_reason, buy_time, sell_time " +
                    "FROM t_closed_position WHERE account_id = ? ORDER BY sell_time ASC";
            try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, acctId);
                java.sql.ResultSet rs = ps.executeQuery();
                DateTimeFormatter dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                while (rs.next()) {
                    java.time.LocalDateTime buyTime = null;
                    String btStr = rs.getString("buy_time");
                    if (btStr != null && !btStr.isEmpty()) {
                        try { buyTime = java.time.LocalDateTime.parse(btStr, dtFmt); } catch (Exception ignored) {}
                    }
                    java.time.LocalDateTime sellTime = null;
                    String stStr = rs.getString("sell_time");
                    if (stStr != null && !stStr.isEmpty()) {
                        try { sellTime = java.time.LocalDateTime.parse(stStr, dtFmt); } catch (Exception ignored) {}
                    }
                    com.stocktrader.model.ClosedPosition cp = com.stocktrader.model.ClosedPosition.builder()
                            .stockCode(rs.getString("stock_code"))
                            .stockName(rs.getString("stock_name"))
                            .quantity(rs.getInt("quantity"))
                            .avgCost(rs.getDouble("avg_cost"))
                            .sellPrice(rs.getDouble("sell_price"))
                            .totalFee(rs.getDouble("total_fee"))
                            .realizedPnl(rs.getDouble("realized_pnl"))
                            .realizedPnlRate(rs.getDouble("realized_pnl_rate"))
                            .closeReason(rs.getString("close_reason"))
                            .buyTime(buyTime)
                            .sellTime(sellTime)
                            .build();
                    result.add(cp);
                }
            }
        } catch (Exception e) {
            log.warn("从数据库加载已平仓记录失败 acctId={}: {}", acctId, e.getMessage());
        } finally {
            if (conn != null) { try { conn.close(); } catch (Exception ignore) {} }
        }
        return result;
    }

    // =================== 撤销记录页 ===================

    /**
     * 撤销记录页：展示所有因实盘操作失败而触发的模拟账户回滚记录，可用于后续分析。
     */
    private String pageRollbackRecords(User user) {
        // 直接使用 userId 构建账户ID，删除无用的路径操作死代码
        String userId = user.getUserId();
        String acctId = userId + "_auto_main";

        java.util.List<java.util.Map<String, Object>> records = new java.util.ArrayList<>();
        // [P0-1 优化] 使用独立短连接
        java.sql.Connection conn2 = null;
        try {
            conn2 = com.stocktrader.config.DatabaseManager.getInstance().newConnection();
            String sql = "SELECT id, rollback_type, stock_code, stock_name, sim_price, sim_quantity, " +
                         "sim_amount, refund_cash, rollback_reason, rollback_time " +
                         "FROM t_rollback_record WHERE account_id=? ORDER BY rollback_time DESC LIMIT 200";
            try (java.sql.PreparedStatement ps = conn2.prepareStatement(sql)) {
                ps.setString(1, acctId);
                java.sql.ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("type", rs.getString("rollback_type"));
                    row.put("code", rs.getString("stock_code"));
                    row.put("name", rs.getString("stock_name") != null ? rs.getString("stock_name") : rs.getString("stock_code"));
                    row.put("price", rs.getDouble("sim_price"));
                    row.put("qty", rs.getInt("sim_quantity"));
                    row.put("amount", rs.getDouble("sim_amount"));
                    row.put("refund", rs.getDouble("refund_cash"));
                    row.put("reason", rs.getString("rollback_reason") != null ? rs.getString("rollback_reason") : "");
                    row.put("time", rs.getString("rollback_time"));
                    records.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("查询撤销记录失败: {}", e.getMessage());
        } finally {
            if (conn2 != null) { try { conn2.close(); } catch (Exception ignore) {} }
        }

        // 统计汇总
        long buyFailCount = records.stream().filter(r -> "BUY_FAIL".equals(r.get("type"))).count();
        long sellFailCount = records.stream().filter(r -> "SELL_FAIL".equals(r.get("type"))).count();
        double totalRefund = records.stream().mapToDouble(r -> (Double) r.get("refund")).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='d-flex justify-content-between align-items-center mb-3'>");
        sb.append("<h5 class='mb-0'>🔄 实盘撤销记录</h5>");
        sb.append("<a href='/dashboard' class='btn btn-sm btn-outline-secondary'>← 返回控制台</a>");
        sb.append("</div>");

        // 统计卡片
        sb.append("<div class='row g-3 mb-4'>");
        sb.append(String.format("<div class='col-md-3'><div class='card text-center'><div class='card-body'>" +
                "<div class='text-muted small'>总撤销次数</div>" +
                "<div class='fs-4 fw-bold text-danger'>%d 次</div></div></div></div>", records.size()));
        sb.append(String.format("<div class='col-md-3'><div class='card text-center'><div class='card-body'>" +
                "<div class='text-muted small'>买入失败撤销</div>" +
                "<div class='fs-4 fw-bold text-warning'>%d 次</div></div></div></div>", buyFailCount));
        sb.append(String.format("<div class='col-md-3'><div class='card text-center'><div class='card-body'>" +
                "<div class='text-muted small'>卖出失败撤销</div>" +
                "<div class='fs-4 fw-bold text-info'>%d 次</div></div></div></div>", sellFailCount));
        sb.append(String.format("<div class='col-md-3'><div class='card text-center'><div class='card-body'>" +
                "<div class='text-muted small'>累计退/扣资金</div>" +
                "<div class='fs-4 fw-bold'>%+,.2f 元</div></div></div></div>", totalRefund));
        sb.append("</div>");

        // 明细表格
        sb.append("<div class='card'><div class='card-header fw-bold'>撤销明细（最近200条）</div><div class='card-body p-0'>");
        if (records.isEmpty()) {
            sb.append("<p class='text-muted p-3 mb-0'>暂无撤销记录。当用户反馈实盘交易失败时，系统将自动撤销模拟账户中对应的交易并在此记录。</p>");
        } else {
            sb.append("<div class='table-responsive'>");
            sb.append("<table class='table table-sm table-striped mb-0'>");
            sb.append("<thead class='table-light'><tr>" +
                    "<th>#</th><th>类型</th><th>代码</th><th>名称</th>" +
                    "<th>模拟价格</th><th>数量(股)</th><th>模拟金额</th>" +
                    "<th>退/扣资金</th><th>撤销时间</th><th>原因</th></tr></thead><tbody>");
            for (java.util.Map<String, Object> r : records) {
                String typeStr = "BUY_FAIL".equals(r.get("type"))
                        ? "<span class='badge bg-warning text-dark'>买入失败</span>"
                        : "<span class='badge bg-info text-dark'>卖出失败</span>";
                String reasonFull = htmlEscape(r.get("reason").toString());
                String reasonShort = reasonFull.length() > 30 ? reasonFull.substring(0, 30) + "…" : reasonFull;
                sb.append(String.format(
                    "<tr><td class='text-muted'>%s</td><td>%s</td>" +
                    "<td><b>%s</b></td><td>%s</td>" +
                    "<td>%.2f 元</td><td>%d</td><td>%,.2f 元</td>" +
                    "<td class='fw-bold'>%+,.2f 元</td>" +
                    "<td><small>%s</small></td>" +
                    "<td><small class='text-muted' title='%s'>%s</small></td></tr>",
                    r.get("id"), typeStr,
                    htmlEscape(r.get("code").toString()),
                    htmlEscape(r.get("name").toString()),
                    (Double) r.get("price"), (Integer) r.get("qty"), (Double) r.get("amount"),
                    (Double) r.get("refund"),
                    htmlEscape(r.get("time").toString()),
                    reasonFull, reasonShort));
            }
            sb.append("</tbody></table></div>");
        }
        sb.append("</div></div>");
        return sb.toString();
    }

    // =================== HTML 工具 ===================

    private HttpResponse buildPage(User user, String content) {
        String nav = user != null
                ? String.format("<nav class='navbar navbar-dark bg-primary px-4'>"
                    + "<span class='navbar-brand'>📈 智能交易平台</span>"
                    + "<span class='text-white'>👤 %s</span>"
                    + "<a href='/logout' class='btn btn-sm btn-outline-light ms-3'>退出</a></nav>",
                    htmlEscape(user.getNickname()))
                : "<nav class='navbar navbar-dark bg-primary px-4'><span class='navbar-brand'>📈 智能交易平台</span></nav>";

        String html = "<!DOCTYPE html><html lang='zh'><head>"
                + "<meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>智能交易平台</title>"
                + "<link rel='stylesheet' href='https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css'>"
                + "<style>body{background:#f8f9fa}.stat-card{border-radius:12px;border:none}</style>"
                + "</head><body>"
                + nav
                + "<div class='container-fluid py-4'>" + content + "</div>"
                + "</body></html>";

        HttpResponse resp = new HttpResponse();
        resp.status = "200 OK";
        resp.body = html;
        return resp;
    }

    private HttpResponse redirect(String location) {
        HttpResponse resp = new HttpResponse();
        resp.status = "302 Found";
        resp.location = location;
        resp.body = "";
        return resp;
    }

    private String statCard(String title, String value, String color) {
        return String.format(
            "<div class='col-sm-6 col-lg-3'><div class='card stat-card border-0 shadow-sm'>"
            + "<div class='card-body'><div class='text-muted small'>%s</div>"
            + "<div class='fs-5 fw-bold text-%s'>%s</div></div></div></div>",
            title, color, htmlEscape(value));
    }

    private String formGroup(String label, String name, String type, String placeholder) {
        return String.format(
            "<div class='mb-3'><label class='form-label'>%s</label>"
            + "<input type='%s' name='%s' class='form-control' placeholder='%s'></div>",
            label, type, name, placeholder);
    }

    private String paramInput(String label, String name, String value, String placeholder) {
        return String.format(
            "<div class='col-sm-6 col-md-3'><label class='form-label small'>%s</label>"
            + "<input type='number' name='%s' class='form-control form-control-sm' value='%s' placeholder='%s'></div>",
            label, name, value, placeholder);
    }

    private String alertHtml(String type, String msg) {
        return "<div class='alert alert-" + type + " mt-2'>" + htmlEscape(msg) + "</div>";
    }

    private String htmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private String extractCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return "";
        for (String part : cookieHeader.split(";")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length == 2 && name.equals(kv[0].trim())) return kv[1].trim();
        }
        return "";
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> map = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String k = pair.substring(0, eq);
                String v = pair.substring(eq + 1);
                // 使用 StandardCharsets 替代废弃的 String 参数方式，同时对键名也做解码
                try { k = URLDecoder.decode(k, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                try { v = URLDecoder.decode(v, StandardCharsets.UTF_8.name()); } catch (Exception ignored) {}
                map.put(k, v);
            }
        }
        return map;
    }

    private double parseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static class HttpResponse {
        String status = "200 OK";
        String body = "";
        String setCookie;
        String location;
        boolean clearCookie;
    }
}

