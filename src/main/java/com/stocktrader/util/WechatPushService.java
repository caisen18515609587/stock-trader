package com.stocktrader.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stocktrader.config.SystemConfig;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信推送服务（双通道：个人公众号模板消息 + Server酱降级）
 * <p>
 * ===== 推送通道优先级 =====
 * 1. 【主通道】个人订阅号/服务号模板消息 API（无条数限制，无需第三方）
 *    需要：AppID + AppSecret + 消息模板ID + 用户openid
 *    配置：push.mp.appid / push.mp.appsecret / push.mp.template.trade / push.mp.template.daily
 *    用户侧：push.mp.openid（全局默认）或 User.wechatOpenId（用户专属）
 * 2. 【备用通道】Server酱（原有方案，免费版每日5条）
 *    配置：push.wechat.sendkey（原有配置，无需修改）
 * <p>
 * ===== 公众号配置步骤 =====
 * 1. 登录 https://mp.weixin.qq.com 获取 AppID 和 AppSecret
 * 2. 在【功能 -> 消息模板】中添加两个模板（推荐从模板库选取"交易提醒"/"账户汇总"）
 *    - 交易信号模板（用于买入/卖出通知）：占位符 first/keyword1/keyword2/keyword3/remark
 *    - 日报汇总模板（用于每日盈亏汇总）：同上
 * 3. 获取关注公众号用户的 openid
 *    - 方式一：公众号后台「用户管理」查看
 *    - 方式二：用户关注后通过事件推送获取（需配置服务器）
 *    - 方式三：https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo 测试号可直接查看扫码用户openid
 * 4. 将上述信息填入 application.properties
 * <p>
 * ===== 注意事项 =====
 * - 订阅号（个人可申请）：模板消息需申请开通，个人订阅号一般无法使用；
 *   建议使用【微信公众平台测试账号】（免费，无需审核，完整模板消息功能）进行开发和使用。
 *   测试号地址：https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo
 * - 服务号（企业）：直接支持模板消息，条数无限制。
 * - access_token 有效期 7200 秒，本服务每 110 分钟自动刷新。
 */
public class WechatPushService {

    private static final Logger log = LoggerFactory.getLogger(WechatPushService.class);

    // ===== 微信公众号 API =====
    private static final String MP_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=%s&secret=%s";
    private static final String MP_TEMPLATE_SEND_URL =
            "https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=%s";

    // ===== Server酱（降级备用）=====
    private static final String SERVER_CHAN_URL = "https://sctapi.ftqq.com/%s.send";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ===== 公众号配置 =====
    private final String mpAppId;
    private final String mpAppSecret;
    private final String mpTemplateIdTrade;   // 交易信号模板ID（买入/卖出）
    private final String mpTemplateIdDaily;   // 日报汇总模板ID
    private final boolean mpEnabled;          // 公众号推送是否启用

    // ===== [P1-4 优化] access_token 改为全局静态共享 =====
    // 所有 WechatPushService 实例共用同一个 AppID/AppSecret，没有必要每个实例各自独立刻新 token
    // 这样可将刷新线程从 N 个（多用户）减少到 1 个，同时避免并发刷新导致的 token 纻战
    private static final AtomicReference<String> SHARED_ACCESS_TOKEN = new AtomicReference<>("");
    private static volatile long sharedTokenExpireAt = 0L;
    private static volatile ScheduledExecutorService sharedTokenScheduler = null;
    private static final Object TOKEN_INIT_LOCK = new Object();

    // 为兼容旧代码保留实例属性备用（实际指向静态字段）
    @Deprecated
    private final AtomicReference<String> accessToken = SHARED_ACCESS_TOKEN;
    @Deprecated
    private volatile long tokenExpireAt = 0L; // 不再使用，保留属性备用
    private final ScheduledExecutorService tokenRefreshScheduler;

    // ===== Server酱降级配置 =====
    private final String sendKey;
    private final boolean serverChanEnabled;

    // ===== 用户专属 openid（公众号推送目标）=====
    private final String openId;

    /**
     * 回调服务器基础 URL，格式如 http://192.168.1.100:8888
     * AutoTrader 在回调服务器启动后通过 setCallbackBaseUrl() 注入。
     */
    private volatile String callbackBaseUrl = "";

    // ========== 单例（全局配置）==========
    private static volatile WechatPushService instance;

    // ========== 构造函数 ==========

    /** 全局单例构造（读取 application.properties） */
    private WechatPushService() {
        SystemConfig config = SystemConfig.getInstance();
        this.mpAppId            = config.get("push.mp.appid", "");
        this.mpAppSecret        = config.get("push.mp.appsecret", "");
        this.mpTemplateIdTrade  = config.get("push.mp.template.trade", "");
        this.mpTemplateIdDaily  = config.get("push.mp.template.daily", "");
        this.openId             = config.get("push.mp.openid", "");
        this.mpEnabled          = !mpAppId.isEmpty() && !mpAppSecret.isEmpty()
                && config.getBoolean("push.mp.enabled", true);
        this.sendKey            = config.get("push.wechat.sendkey", "");
        this.serverChanEnabled  = !sendKey.isEmpty() && config.getBoolean("push.wechat.enabled", true);

        this.httpClient = buildHttpClient();
        this.tokenRefreshScheduler = initTokenRefreshScheduler();
        logInitInfo("全局");
    }

    /**
     * 用户专属构造（指定 openid 和可选的 SendKey 降级）
     *
     * @param openId      用户微信 openid（公众号模板消息接收者）
     * @param userSendKey 用户 Server酱 SendKey（降级备用，可为空）
     */
    private WechatPushService(String openId, String userSendKey) {
        SystemConfig config = SystemConfig.getInstance();
        // 公众号配置复用全局 AppID/AppSecret/模板
        this.mpAppId            = config.get("push.mp.appid", "");
        this.mpAppSecret        = config.get("push.mp.appsecret", "");
        this.mpTemplateIdTrade  = config.get("push.mp.template.trade", "");
        this.mpTemplateIdDaily  = config.get("push.mp.template.daily", "");
        // openid：优先使用传入的用户专属，否则回退全局
        this.openId = (openId != null && !openId.isEmpty()) ? openId : config.get("push.mp.openid", "");
        this.mpEnabled = !mpAppId.isEmpty() && !mpAppSecret.isEmpty()
                && config.getBoolean("push.mp.enabled", true);
        // Server酱降级：优先用户专属 Key，否则用全局配置
        String key = (userSendKey != null && !userSendKey.isEmpty())
                ? userSendKey : config.get("push.wechat.sendkey", "");
        this.sendKey           = key;
        this.serverChanEnabled = !key.isEmpty();

        this.httpClient = buildHttpClient();
        this.tokenRefreshScheduler = initTokenRefreshScheduler();
        String oidMask = this.openId.length() > 6
                ? this.openId.substring(0, 6) + "***" : this.openId;
        logInitInfo("用户[openid=" + oidMask + "]");
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    private ScheduledExecutorService initTokenRefreshScheduler() {
        if (!mpEnabled) return null;
        // [P1-4 优化] 全局共享刷新线程：所有实例共用同一个 Scheduler，避免多用户场景下 N 个刷新线程并存
        synchronized (TOKEN_INIT_LOCK) {
            if (sharedTokenScheduler != null) {
                // 已局层初始化（可能由全局实例或先创建的用户实例建立），直接复用
                log.debug("[P1-4] access_token 刷新线程已存在，共享复用（不页新建延线程）");
                return sharedTokenScheduler;
            }
            // 立即获取一次 token，之后每 110 分钟刷新（token 有效期 7200 秒 = 120 分钟）
            ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "wx-token-refresher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(this::refreshAccessToken, 0, 110, TimeUnit.MINUTES);
            sharedTokenScheduler = scheduler;
            log.info("[P1-4] 全局共享 access_token 刷新线程已启动");
            return scheduler;
        }
    }

    private void logInitInfo(String scope) {
        if (mpEnabled) {
            String appIdMask = mpAppId.length() > 4 ? mpAppId.substring(0, 4) + "***" : mpAppId;
            String oidMask   = openId.length() > 6 ? openId.substring(0, 6) + "***" : openId;
            log.info("[微信推送] {}：公众号模板消息已启用，AppID={}，openid={}",
                    scope, appIdMask, oidMask);
        }
        if (serverChanEnabled) {
            String keyMask = sendKey.length() > 6 ? sendKey.substring(0, 6) + "***" : sendKey;
            log.info("[微信推送] {}：Server酱{}已启用（SendKey={}）",
                    scope, mpEnabled ? "（降级备用）" : "", keyMask);
        }
        if (!mpEnabled && !serverChanEnabled) {
            log.warn("[微信推送] 未配置任何推送通道，交易信号将不会推送。" +
                    "请配置 push.mp.appid/push.mp.appsecret 或 push.wechat.sendkey");
        }
    }

    // ========== 单例 / 工厂方法 ==========

    public static WechatPushService getInstance() {
        if (instance == null) {
            synchronized (WechatPushService.class) {
                if (instance == null) {
                    instance = new WechatPushService();
                }
            }
        }
        return instance;
    }

    /**
     * 为用户创建专属推送实例（主推荐方式）
     *
     * @param wechatOpenId 用户关注公众号后的 openid（接收模板消息）
     * @param userSendKey  用户 Server酱 SendKey（降级备用，可传 null 或空字符串）
     */
    public static WechatPushService forUser(String wechatOpenId, String userSendKey) {
        boolean hasOpenId  = wechatOpenId != null && !wechatOpenId.isEmpty();
        boolean hasSendKey = userSendKey  != null && !userSendKey.isEmpty();
        if (hasOpenId || hasSendKey) {
            return new WechatPushService(wechatOpenId, userSendKey);
        }
        return getInstance();
    }

    /**
     * 兼容旧接口（仅 SendKey，无 openid）
     * 新代码请使用 forUser(openId, sendKey)
     */
    public static WechatPushService forUser(String userSendKey) {
        return forUser(null, userSendKey);
    }

    // ========== access_token 管理 ==========

    /**
     * 向微信服务器申请/刷新 access_token
     * [P1-4 优化] 写入全局静态共享字段，所有实例立即可用新 token
     */
    private synchronized void refreshAccessToken() {
        if (!mpEnabled) return;
        String url = String.format(MP_TOKEN_URL, mpAppId, mpAppSecret);
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            JsonNode node = objectMapper.readTree(body);
            if (node.has("access_token")) {
                String newToken  = node.get("access_token").asText();
                long   expiresIn = node.has("expires_in") ? node.get("expires_in").asLong(7200) : 7200;
                // [P1-4] 写入全局共享 token
                SHARED_ACCESS_TOKEN.set(newToken);
                sharedTokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L; // 提前5分钟
                log.info("[微信Token] 刷新成功，有效期 {} 秒（全局共享）", expiresIn);
            } else {
                log.warn("[微信Token] 刷新失败，响应: {}", body);
            }
        } catch (Exception e) {
            log.error("[微信Token] 刷新异常: {}", e.getMessage());
        }
    }

    /**
     * 获取有效的 access_token（过期时自动刷新）
     * [P1-4 优化] 读取全局共享 token
     */
    private String getValidToken() {
        if (System.currentTimeMillis() > sharedTokenExpireAt || SHARED_ACCESS_TOKEN.get().isEmpty()) {
            refreshAccessToken();
        }
        return SHARED_ACCESS_TOKEN.get();
    }

    // ========== 公共接口 ==========

    /** 设置交易反馈回调服务器基础 URL（如 http://192.168.1.100:8888） */
    public void setCallbackBaseUrl(String baseUrl) {
        this.callbackBaseUrl = (baseUrl != null) ? baseUrl.replaceAll("/$", "") : "";
        log.info("[微信推送] 回调地址已设置: {}", this.callbackBaseUrl);
    }

    /** 是否已启用任一推送通道 */
    public boolean isEnabled() {
        return mpEnabled || serverChanEnabled;
    }

    // ========== 业务推送方法 ==========

    /**
     * 发送买入信号通知
     */
    public void sendBuySignal(String accountName, String stockCode, String stockName, double price, int quantity,
                               double amount, String reason, double totalAssets, double cashLeft,
                               String positionSummary) {
        if (!isEnabled()) return;

        String accountTag = (accountName != null && !accountName.isEmpty()) ? "[" + accountName + "] " : "";
        String title      = String.format("📈 %s买入信号 - %s %s", accountTag, stockCode, stockName);
        String safeReason = reason != null && !reason.isEmpty() ? reason : "-";
        String posSection = (positionSummary != null && !positionSummary.isEmpty())
                ? positionSummary : "暂无其他持仓";

        // 主通道：公众号模板消息
        boolean mpSent = false;
        if (mpEnabled && !openId.isEmpty() && !mpTemplateIdTrade.isEmpty()) {
            // keyword3 显示操作类型+金额，remark 包含原因、剩余资金和当前持仓摘要
            String keyword2 = String.format("%.2f元 × %d股 = %,.2f元", price, quantity, amount);
            String keyword3 = String.format("买入 | 总资产 %,.2f元", totalAssets);
            String remark   = String.format("原因：%s\n剩余资金：%,.2f元\n当前持仓：%s\n⚠️T+1明日可卖",
                    safeReason, cashLeft, posSection);
            // 微信模板 remark 字段有长度限制，截断到200字
            if (remark.length() > 200) remark = remark.substring(0, 197) + "...";
            mpSent = sendTradeTemplateMsg(openId, title,
                    stockCode + " " + stockName,
                    keyword2,
                    keyword3,
                    remark);
            // 主通道成功时额外通过 Server酱 推回调链接，让用户能点击确认实盘结果
            if (mpSent && serverChanEnabled && callbackBaseUrl != null && !callbackBaseUrl.isEmpty()) {
                sendServerChan("📲 " + accountTag + "请确认买入操作 - " + stockCode,
                        buildBuyFeedbackLinks(stockCode, price, quantity));
            }
        }

        // 降级：Server酱（主通道失败时，完整 Markdown 格式含回调链接）
        if (!mpSent && serverChanEnabled) {
            String content = String.format(
                    "## 📈 %s模拟买入成交通知\n\n" +
                    "| 项目 | 内容 |\n" +
                    "|------|------|\n" +
                    "| 账户 | **%s** |\n" +
                    "| 股票 | **%s %s** |\n" +
                    "| 买入价格 | **%.2f 元** |\n" +
                    "| 买入数量 | **%d 股（%d手）** |\n" +
                    "| 买入金额 | **%,.2f 元** |\n" +
                    "| 触发原因 | %s |\n" +
                    "| 当前总资产 | %,.2f 元 |\n" +
                    "| 剩余可用资金 | %,.2f 元 |\n\n" +
                    "> ⚠️ **T+1规则：今日买入，明日方可卖出**\n\n" +
                    "### 📊 买入后持仓\n\n%s\n\n---\n\n%s",
                    accountTag,
                    accountName != null ? accountName : "",
                    stockCode, stockName, price, quantity, quantity / 100, amount,
                    safeReason, totalAssets, cashLeft,
                    posSection,
                    buildBuyFeedbackLinks(stockCode, price, quantity)
            );
            sendServerChan(title, content);
        }
    }

    /**
     * 发送卖出信号通知
     */
    public void sendSellSignal(String accountName, String stockCode, String stockName, double sellPrice, double costPrice,
                                int quantity, double amount, double pnl, double pnlRate,
                                String reason, double totalReturn, String positionSummary) {
        if (!isEnabled()) return;

        String emoji      = pnl >= 0 ? "💰" : "📉";
        String accountTag = (accountName != null && !accountName.isEmpty()) ? "[" + accountName + "] " : "";
        String title      = String.format("%s %s卖出信号 - %s %s  盈亏: %+.2f%%",
                emoji, accountTag, stockCode, stockName, pnlRate);
        String safeReason = reason != null && !reason.isEmpty() ? reason : "-";
        String posSection = (positionSummary != null && !positionSummary.isEmpty())
                ? positionSummary : "卖出后空仓";

        // 主通道：公众号模板消息
        boolean mpSent = false;
        if (mpEnabled && !openId.isEmpty() && !mpTemplateIdTrade.isEmpty()) {
            // keyword2 = 卖出明细，keyword3 = 盈亏结果，remark = 原因+剩余持仓+回调提示
            String keyword2 = String.format("%.2f元 × %d股 = %,.2f元（成本%.2f）", sellPrice, quantity, amount, costPrice);
            String keyword3 = String.format("%s%+.2f元（%+.2f%%）| 总收益%+.2f%%",
                    pnl >= 0 ? "盈利 " : "亏损 ", pnl, pnlRate, totalReturn);
            String callbackHint = (callbackBaseUrl != null && !callbackBaseUrl.isEmpty())
                    ? "\n点击消息可反馈实盘结果" : "";
            String remark = String.format("原因：%s\n卖后持仓：%s%s",
                    safeReason, posSection, callbackHint);
            if (remark.length() > 200) remark = remark.substring(0, 197) + "...";
            mpSent = sendTradeTemplateMsg(openId, title,
                    stockCode + " " + stockName,
                    keyword2,
                    keyword3,
                    remark);
            // 主通道成功时额外通过回调链接推一条 Server酱（如果有配置），让用户能点击确认
            if (mpSent && serverChanEnabled && callbackBaseUrl != null && !callbackBaseUrl.isEmpty()) {
                sendServerChan("📲 " + accountTag + "请确认卖出操作 - " + stockCode,
                        buildSellFeedbackLinks(stockCode, sellPrice, quantity));
            }
        }

        // 降级：Server酱（主通道失败时，完整 Markdown 格式）
        if (!mpSent && serverChanEnabled) {
            String content = String.format(
                    "## %s %s模拟卖出成交通知\n\n" +
                    "| 项目 | 内容 |\n" +
                    "|------|------|\n" +
                    "| 账户 | **%s** |\n" +
                    "| 股票 | **%s %s** |\n" +
                    "| 卖出价格 | **%.2f 元** |\n" +
                    "| 买入成本 | %.2f 元 |\n" +
                    "| 卖出数量 | **%d 股（%d手）** |\n" +
                    "| 卖出金额 | **%,.2f 元** |\n" +
                    "| **本次盈亏** | **%+,.2f 元（%+.2f%%）** |\n" +
                    "| 触发原因 | %s |\n" +
                    "| 账户总收益率 | %+.2f%% |\n\n" +
                    "### 📊 卖出后持仓\n\n%s\n\n---\n\n%s",
                    emoji, accountTag,
                    accountName != null ? accountName : "",
                    stockCode, stockName, sellPrice, costPrice,
                    quantity, quantity / 100, amount, pnl, pnlRate,
                    safeReason, totalReturn,
                    posSection,
                    buildSellFeedbackLinks(stockCode, sellPrice, quantity)
            );
            sendServerChan(title, content);
        }
    }

    /**
     * 发送每日汇总推送
     */
    public void sendDailySummary(String accountName, double totalAssets, double initialCapital,
                                  double totalProfit, double totalReturn,
                                  double dailyProfit, double dailyReturn,
                                  String positionSummary) {
        if (!isEnabled()) return;

        String dailyEmoji = dailyProfit >= 0 ? "📈" : "📉";
        String totalEmoji = totalProfit >= 0 ? "🟢" : "🔴";
        String accountTag = (accountName != null && !accountName.isEmpty()) ? "[" + accountName + "] " : "";
        String title      = String.format("%s %s今日交易汇总  当日: %+.2f 元（%+.2f%%）",
                dailyEmoji, accountTag, dailyProfit, dailyReturn);
        String posSummary = (positionSummary == null || positionSummary.isEmpty())
                ? "当前空仓" : positionSummary;

        // 主通道：公众号模板消息
        boolean mpSent = false;
        if (mpEnabled && !openId.isEmpty() && !mpTemplateIdDaily.isEmpty()) {
            String keyword1 = accountName != null ? accountName : "账户";
            // keyword2 = 总资产/累计收益，keyword3 = 当日盈亏
            String keyword2 = String.format("%,.2f元 | 累计%s%.2f%%",
                    totalAssets, totalProfit >= 0 ? "+" : "", totalReturn);
            String keyword3 = String.format("%+,.2f元（%+.2f%%）",  dailyProfit, dailyReturn);
            // remark：持仓摘要，截断到200字
            String remark = "持仓：" + posSummary;
            if (remark.length() > 200) remark = remark.substring(0, 197) + "...";
            mpSent = sendDailyTemplateMsg(openId, title, keyword1, keyword2, keyword3, remark);
        }

        // Server酱：主通道成功时仅补充详细状态链接；失败时发完整报告
        String statusLink = (callbackBaseUrl != null && !callbackBaseUrl.isEmpty())
                ? String.format("\n\n📊 [点击查看账户实时持仓](%s/status)", callbackBaseUrl) : "";

        if (mpSent && serverChanEnabled && !statusLink.isEmpty()) {
            // 主通道已推送，Server酱仅补一条带状态链接的简短通知
            sendServerChan(title,
                    String.format("## %s %s今日交易汇总\n\n" +
                            "| 当日盈亏 | 累计盈亏 | 总资产 |\n" +
                            "|---------|---------|-------|\n" +
                            "| **%+,.2f元（%+.2f%%）** | **%s%+.2f元（%+.2f%%）** | **%,.2f元** |\n\n" +
                            "### 📊 当前持仓\n\n%s%s",
                            dailyEmoji, accountTag,
                            dailyProfit, dailyReturn,
                            totalEmoji, totalProfit, totalReturn, totalAssets,
                            posSummary, statusLink));
        } else if (!mpSent && serverChanEnabled) {
            // 主通道失败，Server酱发完整报告
            String content = String.format(
                    "## %s %s每日交易汇总报告\n\n" +
                    "| 项目 | 金额 |\n" +
                    "|------|------|\n" +
                    "| 账户 | **%s** |\n" +
                    "| 初始资金 | %,.2f 元 |\n" +
                    "| 当前总资产 | **%,.2f 元** |\n" +
                    "| 📅 当日盈亏 | **%+,.2f 元（%+.2f%%）** |\n" +
                    "| %s 累计总盈亏 | **%+,.2f 元（%+.2f%%）** |\n\n" +
                    "### 📊 当前持仓\n\n%s%s",
                    dailyEmoji, accountTag,
                    accountName != null ? accountName : "",
                    initialCapital, totalAssets,
                    dailyProfit, dailyReturn,
                    totalEmoji, totalProfit, totalReturn,
                    posSummary, statusLink
            );
            sendServerChan(title, content);
        }
    }

    /**
     * 发送系统启动通知
     */
    public void sendSystemStart(double initialCapital, int topN, int scanInterval) {
        if (!isEnabled()) return;
        String title   = "🚀 自动交易系统已启动";
        String content = String.format(
                "初始资金: %.2f元 | 最大持股: %d只 | 扫描间隔: %d分钟\n策略: 短线做T（T+1规则）",
                initialCapital, topN, scanInterval);
        sendMessage(title, content);
    }

    /**
     * 发送自定义消息（自动选择最优通道）
     */
    public void sendMessage(String title, String content) {
        if (!isEnabled()) return;

        // 主通道：公众号模板消息（通用格式，标题->first，内容->remark）
        boolean mpSent = false;
        if (mpEnabled && !openId.isEmpty() && !mpTemplateIdTrade.isEmpty()) {
            mpSent = sendTradeTemplateMsg(openId, title, "-", "-", "通知", content);
        }

        // 降级：Server酱
        if (!mpSent && serverChanEnabled) {
            sendServerChan(title, content);
        }
    }

    // ========== 公众号模板消息（私有）==========

    /**
     * 发送交易信号类模板消息
     * <p>
     * 推荐模板内容（在公众号后台「功能 -> 消息模板」新建，行业不限，内容如下）：
     * <pre>
     * 模板标题：交易信号通知
     * 模板内容（每行一个变量）：
     * {{first.DATA}}
     * 股票：{{keyword1.DATA}}
     * 金额：{{keyword2.DATA}}
     * 类型：{{keyword3.DATA}}
     * {{remark.DATA}}
     * </pre>
     */
    private boolean sendTradeTemplateMsg(String targetOpenId, String first,
                                          String keyword1, String keyword2, String keyword3,
                                          String remark) {
        return sendTemplateMsg(targetOpenId, mpTemplateIdTrade,
                first, keyword1, keyword2, keyword3, remark);
    }

    /**
     * 发送日报汇总类模板消息
     * <p>
     * 推荐模板内容：
     * <pre>
     * 模板标题：账户每日汇总
     * 模板内容：
     * {{first.DATA}}
     * 账户：{{keyword1.DATA}}
     * 总资产：{{keyword2.DATA}}
     * 今日盈亏：{{keyword3.DATA}}
     * {{remark.DATA}}
     * </pre>
     */
    private boolean sendDailyTemplateMsg(String targetOpenId, String first,
                                          String keyword1, String keyword2, String keyword3,
                                          String remark) {
        return sendTemplateMsg(targetOpenId, mpTemplateIdDaily,
                first, keyword1, keyword2, keyword3, remark);
    }

    /**
     * 通用模板消息发送（支持5个占位符：first, keyword1~3, remark）
     *
     * @return true=发送成功，false=发送失败（触发降级）
     */
    private boolean sendTemplateMsg(String targetOpenId, String templateId,
                                     String first, String keyword1, String keyword2,
                                     String keyword3, String remark) {
        if (templateId == null || templateId.isEmpty()) {
            log.debug("[公众号推送] 未配置模板ID，跳过");
            return false;
        }
        String token = getValidToken();
        if (token == null || token.isEmpty()) {
            log.warn("[公众号推送] access_token 为空，无法推送（AppID/AppSecret是否正确？）");
            return false;
        }

        try {
            // 构建 JSON 请求体
            ObjectNode root = objectMapper.createObjectNode();
            root.put("touser",      targetOpenId);
            root.put("template_id", templateId);

            // 点击消息时跳转到账户状态页
            if (callbackBaseUrl != null && !callbackBaseUrl.isEmpty()) {
                root.put("url", callbackBaseUrl + "/status");
            }

            ObjectNode data = objectMapper.createObjectNode();
            data.set("first",    buildItem(first,    "#333333"));
            data.set("keyword1", buildItem(keyword1, "#333333"));
            data.set("keyword2", buildItem(keyword2, "#333333"));
            data.set("keyword3", buildItem(keyword3, "#FF6600"));
            data.set("remark",   buildItem(remark,   "#999999"));
            root.set("data", data);

            String   jsonBody = objectMapper.writeValueAsString(root);
            String   url      = String.format(MP_TEMPLATE_SEND_URL, token);
            RequestBody body  = RequestBody.create(jsonBody,
                    MediaType.parse("application/json; charset=utf-8"));
            Request request   = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                String   resp     = response.body() != null ? response.body().string() : "{}";
                JsonNode respNode = objectMapper.readTree(resp);
                int errCode = respNode.has("errcode") ? respNode.get("errcode").asInt(-1) : -1;
                if (errCode == 0) {
                    log.info("[公众号推送] 成功: {}", first);
                    return true;
                } else {
                    String errMsg = respNode.has("errmsg") ? respNode.get("errmsg").asText() : "unknown";
                    log.warn("[公众号推送] 失败，errcode={} errmsg={}", errCode, errMsg);
                    // token 失效时立即刷新
                    if (errCode == 40001 || errCode == 42001) {
                        log.info("[公众号推送] Token 失效，立即刷新并将在下次推送时重试");
                        refreshAccessToken();
                    }
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("[公众号推送] 异常: {}", e.getMessage());
            return false;
        }
    }

    /** 构建模板消息数据项 */
    private ObjectNode buildItem(String value, String color) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("value", value != null ? value : "");
        node.put("color", color);
        return node;
    }

    // ========== Server酱降级发送 ==========

    private void sendServerChan(String title, String content) {
        if (!serverChanEnabled) return;
        String      url     = String.format(SERVER_CHAN_URL, sendKey);
        RequestBody body    = new FormBody.Builder()
                .add("title", title)
                .add("desp",  content)
                .build();
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String resp = response.body() != null ? response.body().string() : "";
            if (response.isSuccessful() && resp.contains("\"code\":0")) {
                log.info("[Server酱推送] 成功: {}", title);
            } else {
                log.warn("[Server酱推送] 返回异常: {} | 响应: {}", response.code(), resp);
            }
        } catch (IOException e) {
            log.error("[Server酱推送] 失败: {}", e.getMessage());
        }
    }

    // ========== 回调链接构建 ==========

    private String buildBuyFeedbackLinks(String stockCode, double price, int quantity) {
        if (callbackBaseUrl == null || callbackBaseUrl.isEmpty()) {
            return "> ⚠️ 未配置回调地址，请在 application.properties 中设置 `push.callback.host`\n\n" +
                   "如已在实盘操作，请手动访问回调服务器确认。";
        }
        String okUrl     = String.format("%s/callback?action=buy_ok&code=%s&price=%.2f&qty=%d",
                callbackBaseUrl, stockCode, price, quantity);
        String failUrl   = String.format("%s/callback?action=buy_fail&code=%s",
                callbackBaseUrl, stockCode);
        String statusUrl = callbackBaseUrl + "/status";
        return "### 📲 请在实盘操作后点击确认\n\n" +
               String.format("👉 **[✅ 买入成功（已按信号价格成交）](%s)**\n\n", okUrl) +
               String.format("👉 **[❌ 买入失败（未能成交，撤回模拟）](%s)**\n\n", failUrl) +
               String.format("📊 [查看账户实时状态](%s)", statusUrl);
    }

    private String buildSellFeedbackLinks(String stockCode, double sellPrice, int quantity) {
        if (callbackBaseUrl == null || callbackBaseUrl.isEmpty()) {
            return "> ⚠️ 未配置回调地址，请在 application.properties 中设置 `push.callback.host`\n\n" +
                   "如已在实盘操作，请手动访问回调服务器确认。";
        }
        String okUrl     = String.format("%s/callback?action=sell_ok&code=%s&price=%.2f&qty=%d",
                callbackBaseUrl, stockCode, sellPrice, quantity);
        String failUrl   = String.format("%s/callback?action=sell_fail&code=%s",
                callbackBaseUrl, stockCode);
        String statusUrl = callbackBaseUrl + "/status";
        return "### 📲 请在实盘操作后点击确认\n\n" +
               String.format("👉 **[✅ 卖出成功（已按信号价格成交）](%s)**\n\n", okUrl) +
               String.format("👉 **[❌ 卖出失败（未能成交）](%s)**\n\n", failUrl) +
               String.format("📊 [查看账户实时状态](%s)", statusUrl);
    }
}

