package com.stocktrader.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktrader.model.StrategyConfig;
import com.stocktrader.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户服务：注册、登录、Session管理、策略配置
 */
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final ObjectMapper om = new ObjectMapper();

    // Session超时：8小时
    private static final long SESSION_TIMEOUT_MS = 8 * 60 * 60 * 1000L;

    private final UserStore store;

    // sessionToken -> userId
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    // sessionToken -> 创建时间戳
    private final Map<String, Long> sessionTime = new ConcurrentHashMap<>();

    public UserService(UserStore store) {
        this.store = store;
    }

    // =================== 注册 ===================

    public static class RegisterResult {
        public final boolean success;
        public final String message;
        public final User user;
        RegisterResult(boolean success, String message, User user) {
            this.success = success; this.message = message; this.user = user;
        }
    }

    /**
     * 注册新用户
     *
     * @param username      用户名（4-20位字母数字下划线）
     * @param password      密码（明文，6-30位）
     * @param nickname      昵称
     * @param initialCapital 初始资金
     * @param strategyType  策略类型
     */
    public RegisterResult register(String username, String password, String nickname,
                                    double initialCapital, User.StrategyType strategyType) {
        // 参数校验
        if (username == null || !username.matches("[a-zA-Z0-9_]{4,20}")) {
            return new RegisterResult(false, "用户名须为4-20位字母、数字或下划线", null);
        }
        if (password == null || password.length() < 6 || password.length() > 30) {
            return new RegisterResult(false, "密码须为6-30位", null);
        }
        if (store.usernameExists(username)) {
            return new RegisterResult(false, "用户名「" + username + "」已被注册", null);
        }
        if (initialCapital < 1000) {
            return new RegisterResult(false, "初始资金不能低于1000元", null);
        }

        // 自动生成策略配置
        StrategyConfig sc = buildStrategyConfig(strategyType, initialCapital, null);
        String scJson = toJson(sc);

        User user = User.builder()
                .userId(UUID.randomUUID().toString().replace("-", ""))
                .username(username)
                .passwordHash(sha256(password))
                .nickname(nickname != null && !nickname.isEmpty() ? nickname : username)
                .initialCapital(initialCapital)
                .strategyType(strategyType)
                .strategyConfigJson(scJson)
                .status(User.UserStatus.ACTIVE)
                .createTime(LocalDateTime.now())
                .autoTraderRunning(false)
                .build();

        store.save(user);
        log.info("新用户注册：{} ({})", username, strategyType.getDesc());
        return new RegisterResult(true, "注册成功！", user);
    }

    // =================== 登录 ===================

    public static class LoginResult {
        public final boolean success;
        public final String message;
        public final String sessionToken;
        public final User user;
        LoginResult(boolean success, String message, String sessionToken, User user) {
            this.success = success; this.message = message;
            this.sessionToken = sessionToken; this.user = user;
        }
    }

    public LoginResult login(String username, String password) {
        User user = store.findByUsername(username);
        if (user == null) {
            return new LoginResult(false, "用户名或密码错误", null, null);
        }
        if (user.getStatus() == User.UserStatus.DISABLED) {
            return new LoginResult(false, "账户已被禁用", null, null);
        }
        if (!sha256(password).equals(user.getPasswordHash())) {
            return new LoginResult(false, "用户名或密码错误", null, null);
        }

        // 生成Session Token
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, user.getUserId());
        sessionTime.put(token, System.currentTimeMillis());

        // 更新最后登录时间
        user.setLastLoginTime(LocalDateTime.now());
        store.save(user);

        log.info("用户登录：{}", username);
        return new LoginResult(true, "登录成功", token, user);
    }

    public void logout(String token) {
        sessions.remove(token);
        sessionTime.remove(token);
    }

    /** 根据token获取当前登录用户（null表示未登录或已超时） */
    public User getSessionUser(String token) {
        if (token == null || token.isEmpty()) return null;
        Long ts = sessionTime.get(token);
        if (ts == null || System.currentTimeMillis() - ts > SESSION_TIMEOUT_MS) {
            sessions.remove(token);
            sessionTime.remove(token);
            return null;
        }
        // 刷新session时间
        sessionTime.put(token, System.currentTimeMillis());
        String userId = sessions.get(token);
        return userId != null ? store.findById(userId) : null;
    }

    // =================== 策略配置 ===================

    /**
     * 更新用户策略配置
     *
     * @param userId          用户ID
     * @param strategyType    新策略类型
     * @param customDesc      自定义策略描述（strategyType=CUSTOM时有效）
     * @param overrideParams  可选：直接覆盖特定参数（key=value形式）
     */
    public boolean updateStrategy(String userId, User.StrategyType strategyType,
                                   String customDesc, Map<String, String> overrideParams) {
        User user = store.findById(userId);
        if (user == null) return false;

        StrategyConfig sc = buildStrategyConfig(strategyType, user.getInitialCapital(), customDesc);

        // 处理用户覆盖的参数
        if (overrideParams != null) {
            applyOverrides(sc, overrideParams);
        }

        user.setStrategyType(strategyType);
        user.setStrategyConfigJson(toJson(sc));
        store.save(user);
        log.info("用户 {} 策略已更新为：{}", user.getUsername(), strategyType.getDesc());
        return true;
    }

    /**
     * 更新用户微信推送配置（openid + SendKey）
     *
     * @param userId  用户ID
     * @param openId  微信公众号 openid（主通道，可为空）
     * @param sendKey Server酱 SendKey（降级备用，可为空）
     */
    public boolean updateWechatConfig(String userId, String openId, String sendKey) {
        User user = store.findById(userId);
        if (user == null) return false;
        if (openId  != null) user.setWechatOpenId(openId.trim());
        if (sendKey != null) user.setWechatSendKey(sendKey.trim());
        store.save(user);
        return true;
    }

    /** 更新用户微信SendKey（兼容旧接口） */
    public boolean updateWechatKey(String userId, String sendKey) {
        return updateWechatConfig(userId, null, sendKey);
    }

    /** 获取用户当前策略配置 */
    public StrategyConfig getStrategyConfig(String userId) {
        User user = store.findById(userId);
        if (user == null) return null;
        if (user.getStrategyConfigJson() == null || user.getStrategyConfigJson().isEmpty()) {
            return buildStrategyConfig(user.getStrategyType(), user.getInitialCapital(), null);
        }
        try {
            return om.readValue(user.getStrategyConfigJson(), StrategyConfig.class);
        } catch (Exception e) {
            return buildStrategyConfig(user.getStrategyType(), user.getInitialCapital(), null);
        }
    }

    /** 标记用户自动交易运行状态 */
    public void setAutoTraderRunning(String userId, boolean running) {
        User user = store.findById(userId);
        if (user != null) {
            user.setAutoTraderRunning(running);
            store.save(user);
        }
    }

    public UserStore getStore() { return store; }

    // =================== 超级管理员 ===================

    /**
     * 创建或重置超级管理员账户
     * - 若用户名不存在则注册新账户并设为超管
     * - 若用户名已存在且已是超管则更新密码
     * - 超管账户：不可被其他人禁用/删除，策略和信息只有本人可改
     *
     * @param username  超管用户名
     * @param password  明文密码
     * @return 创建/更新结果描述
     */
    public String initSuperAdmin(String username, String password) {
        User existing = store.findByUsername(username);
        if (existing != null) {
            if (!existing.isSuperAdmin()) {
                return "用户名「" + username + "」已被普通用户占用，无法设为超管";
            }
            // 已是超管，更新密码
            existing.setPasswordHash(sha256(password));
            store.save(existing);
            log.info("超级管理员 {} 密码已更新", username);
            return "超级管理员「" + username + "」密码已更新";
        }

        // 新建超管账户
        StrategyConfig sc = StrategyConfig.buildDayTrade(1000000);
        User admin = User.builder()
                .userId(UUID.randomUUID().toString().replace("-", ""))
                .username(username)
                .passwordHash(sha256(password))
                .nickname("超级管理员")
                .initialCapital(1000000)
                .strategyType(User.StrategyType.DAY_TRADE)
                .strategyConfigJson(toJson(sc))
                .status(User.UserStatus.ACTIVE)
                .superAdmin(true)
                .createTime(LocalDateTime.now())
                .autoTraderRunning(false)
                .build();
        store.save(admin);
        log.info("超级管理员账户已创建：{}", username);
        return "超级管理员「" + username + "」已创建成功";
    }

    /**
     * 判断操作者能否修改目标用户
     * 规则：超管账户只有本人（或另一个超管）可以修改，普通用户不得修改超管
     */
    public boolean canModify(User operator, User target) {
        if (operator == null || target == null) return false;
        if (target.isSuperAdmin() && !operator.isSuperAdmin()) return false;
        return true;
    }

    // =================== 辅助方法 ===================

    private static StrategyConfig buildStrategyConfig(User.StrategyType type, double capital, String customDesc) {
        switch (type) {
            case MEDIUM_LONG:  return StrategyConfig.buildMediumLong(capital);
            case DAY_TRADE:    return StrategyConfig.buildDayTrade(capital);
            case SWAP_STRONG:  return StrategyConfig.buildSwapStrong(capital);
            case CUSTOM:
            default:           return StrategyConfig.buildCustom(customDesc != null ? customDesc : "", capital);
        }
    }

    private static void applyOverrides(StrategyConfig sc, Map<String, String> params) {
        params.forEach((k, v) -> {
            try {
                switch (k) {
                    case "stopLoss":       sc.setStopLossPercent(Double.parseDouble(v) / 100); break;
                    case "takeProfitHalf": sc.setTakeProfitHalfPercent(Double.parseDouble(v) / 100); break;
                    case "takeProfitFull": sc.setTakeProfitFullPercent(Double.parseDouble(v) / 100); break;
                    case "maxPosition":    sc.setMaxPositionRatio(Double.parseDouble(v) / 100); break;
                    case "maxPositions":   sc.setMaxPositions(Integer.parseInt(v)); break;
                    case "topN":           sc.setTopN(Integer.parseInt(v)); break;
                    case "minScore":       sc.setMinScore(Integer.parseInt(v)); break;
                    case "scanInterval":   sc.setScanIntervalMin(Integer.parseInt(v)); break;
                }
            } catch (Exception ignored) {}
        });
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256加密失败", e);
        }
    }

    private static String toJson(Object obj) {
        try { return om.writeValueAsString(obj); } catch (Exception e) { return "{}"; }
    }
}

