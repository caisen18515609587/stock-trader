package com.stocktrader.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * 系统配置管理器
 * <p>
 * 配置优先级（从高到低）：
 * 1. 环境变量（将 key 中的 "." 替换为 "_" 并转大写，如 push.mp.appsecret → PUSH_MP_APPSECRET）
 * 2. application.properties 配置文件
 * 3. 代码中指定的 defaultValue
 * <p>
 * 敏感配置（AppSecret、Token、SendKey、OpenID 等）建议通过环境变量注入，
 * 不要将真实值提交到版本库。application.properties 中对应项留空即可。
 */
public class SystemConfig {

    private static final Logger log = LoggerFactory.getLogger(SystemConfig.class);
    private static final String CONFIG_FILE = "application.properties";

    private static SystemConfig instance;
    private final Properties props;

    private SystemConfig() {
        props = new Properties();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (is != null) {
                // 使用 UTF-8 读取，支持配置文件中的中文注释
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
                log.info("配置文件加载成功：{}", CONFIG_FILE);
            } else {
                log.warn("未找到配置文件：{}，使用默认配置", CONFIG_FILE);
            }
        } catch (Exception e) {
            log.error("加载配置文件失败", e);
        }
        logSensitiveConfigSources();
    }

    /**
     * 打印敏感配置的来源（env / file / missing），方便排查问题，不打印实际值
     */
    private void logSensitiveConfigSources() {
        String[] sensitiveKeys = {
            "datasource.ths.token",
            "push.mp.appid",
            "push.mp.appsecret",
            "push.mp.template.trade",
            "push.mp.template.daily",
            "push.mp.openid",
            "push.wechat.sendkey"
        };
        for (String key : sensitiveKeys) {
            String envKey = key.replace(".", "_").toUpperCase();
            String envVal = System.getenv(envKey);
            if (envVal != null && !envVal.isEmpty()) {
                log.info("敏感配置 [{}] 来源：环境变量 {}", key, envKey);
            } else {
                String fileVal = props.getProperty(key);
                if (fileVal != null && !fileVal.isEmpty()) {
                    log.warn("敏感配置 [{}] 来源：配置文件（建议改用环境变量 {}）", key, envKey);
                } else {
                    log.warn("敏感配置 [{}] 未配置（可通过环境变量 {} 设置）", key, envKey);
                }
            }
        }
    }

    public static synchronized SystemConfig getInstance() {
        if (instance == null) {
            instance = new SystemConfig();
        }
        return instance;
    }

    /**
     * 获取配置值，优先级：环境变量 > 配置文件 > defaultValue
     * 环境变量 key 规则：将属性 key 中的 "." 替换为 "_" 并转大写
     * 例如：push.mp.appsecret → 环境变量 PUSH_MP_APPSECRET
     */
    public String get(String key, String defaultValue) {
        // 1. 优先读取环境变量（key 转大写下划线风格）
        String envKey = key.replace(".", "_").toUpperCase();
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isEmpty()) {
            return envValue;
        }
        // 2. 从配置文件读取
        return props.getProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = get(key, null);
        return val != null ? Boolean.parseBoolean(val) : defaultValue;
    }

    /** 获取系统运行模式 */
    public SystemMode getSystemMode() {
        String mode = get("system.mode", "SIMULATION");
        try {
            return SystemMode.valueOf(mode.toUpperCase());
        } catch (Exception e) {
            return SystemMode.SIMULATION;
        }
    }

    /** 获取模拟账户初始资金 */
    public double getInitialCapital() {
        return getDouble("account.simulation.initial.capital", 100000);
    }

    /** 获取同花顺Token */
    public String getThsToken() {
        return get("datasource.ths.token", null);
    }

    /** 获取监控股票列表 */
    public List<String> getWatchlistStocks() {
        String list = get("watchlist.stocks", "600519,000858,300750");
        return Arrays.asList(list.split(","));
    }

    /** 获取报告输出目录 */
    public String getReportOutputDir() {
        return get("report.output.dir", "./reports");
    }

    /** 单只股票最大仓位比例 */
    public double getMaxSingleStockRatio() {
        return getDouble("risk.single.stock.max.ratio", 0.30);
    }

    /** 账户最大回撤阈值 */
    public double getMaxAccountDrawdown() {
        return getDouble("risk.max.account.drawdown", 0.20);
    }

    /** 最大持股数量 */
    public int getMaxPositions() {
        return getInt("risk.max.positions", 5);
    }

    // ==================== 微信推送配置 ====================

    /** 是否启用微信推送（Server酱降级通道） */
    public boolean isPushEnabled() {
        return getBoolean("push.wechat.enabled", false);
    }

    /** Server酱 SendKey（到 https://sct.ftqq.com 获取，降级备用） */
    public String getPushSendKey() {
        return get("push.wechat.sendkey", "");
    }

    // ===== 公众号模板消息配置（主通道）=====

    /** 是否启用公众号模板消息推送（主通道） */
    public boolean isMpPushEnabled() {
        return getBoolean("push.mp.enabled", true);
    }

    /** 公众号 AppID */
    public String getMpAppId() {
        return get("push.mp.appid", "");
    }

    /** 公众号 AppSecret */
    public String getMpAppSecret() {
        return get("push.mp.appsecret", "");
    }

    /** 交易信号模板消息模板ID（买入/卖出通知） */
    public String getMpTemplateIdTrade() {
        return get("push.mp.template.trade", "");
    }

    /** 日报汇总模板消息模板ID */
    public String getMpTemplateIdDaily() {
        return get("push.mp.template.daily", "");
    }

    /** 全局默认推送 openid（关注公众号的用户 openid） */
    public String getMpOpenId() {
        return get("push.mp.openid", "");
    }

    /** 交易反馈回调服务器端口（默认8888） */
    public int getCallbackServerPort() {
        return getInt("push.callback.port", 8888);
    }

    /** 是否启用交易反馈回调服务器 */
    public boolean isCallbackServerEnabled() {
        return getBoolean("push.callback.enabled", true);
    }

    // ==================== 快速止损检查配置 ====================

    /** 是否启用每分钟快速止损检查（仅拉实时价，不拉K线，响应更快） */
    public boolean isQuickStopLossEnabled() {
        return getBoolean("quick.stoploss.enabled", true);
    }

    /** 快速止损检查间隔（秒），默认30秒（P2优化：由60秒缩短至30秒，提升止损响应速度）*/
    public int getQuickStopLossIntervalSeconds() {
        return getInt("quick.stoploss.interval.seconds", 30);
    }

    // ==================== 基本面选股配置 ====================

    /**
     * 是否在选股时启用基本面过滤（需 Tushare 2000+ 积分）
     * 对应配置项：screen.fundamental.enabled=true/false
     * 默认：false（纯技术面，保证任何积分水平都可运行）
     */
    public boolean isFundamentalScreenEnabled() {
        return getBoolean("screen.fundamental.enabled", false);
    }

    /**
     * 技术面在联合评分中的权重（0~1）
     * 对应配置项：screen.fundamental.tech.weight=0.7
     * 默认：0.7（技术面 70%，基本面 30%）
     */
    public double getFundamentalTechWeight() {
        return getDouble("screen.fundamental.tech.weight", 0.7);
    }

    /**
     * 基本面 ROE 最低阈值（%），低于此值的股票在联合选股中被排除
     * 对应配置项：screen.fundamental.roe.min=10
     */
    public double getFundamentalRoeMin() {
        return getDouble("screen.fundamental.roe.min", 10.0);
    }

    /**
     * 基本面营收同比增速最低阈值（%），低于此值的股票在联合选股中被排除
     * 对应配置项：screen.fundamental.revenue.yoy.min=0
     */
    public double getFundamentalRevenueYoyMin() {
        return getDouble("screen.fundamental.revenue.yoy.min", 0.0);
    }

    // ==================== QMT 实盘交易配置 ====================

    /**
     * 是否启用 QMT 实盘交易模式
     * 对应配置项：live.trade.enabled=true
     * 默认：false（纯模拟，安全）
     */
    public boolean isLiveTradeEnabled() {
        return getBoolean("live.trade.enabled", false);
    }

    /**
     * qmt_bridge.py 服务地址
     * 对应配置项：live.trade.bridge.url=http://localhost:8098
     */
    public String getLiveTradeBridgeUrl() {
        return get("live.trade.bridge.url", "http://localhost:8098");
    }

    /**
     * QMT 账户 ID（迅投 MiniQMT 中显示的资金账号）
     * 对应配置项：live.trade.account.id=xxxxx
     * 若为空，需通过环境变量 QMT_ACCOUNT_ID 设置
     */
    public String getLiveTradeAccountId() {
        return get("live.trade.account.id", "");
    }

    public enum SystemMode {
        SIMULATION("模拟交易"),
        BACKTEST("历史回测"),
        LIVE("实盘交易");

        private final String description;

        SystemMode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}

