package com.stocktrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 平台用户模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    /** 用户唯一ID（UUID） */
    private String userId;

    /** 用户名（登录用，字母+数字，不重复） */
    private String username;

    /** 密码（SHA-256哈希存储） */
    private String passwordHash;

    /** 昵称（显示用） */
    private String nickname;

    /** 邮箱（可选） */
    private String email;

    /** 初始资金（元） */
    private double initialCapital;

    /** 策略类型：MEDIUM_LONG（中长期）/ DAY_TRADE（做T）/ CUSTOM（自定义） */
    private StrategyType strategyType;

    /** 策略配置（JSON序列化的 StrategyConfig） */
    private String strategyConfigJson;

    /**
     * 微信公众号 openid（可选）
     * 用户关注公众号后获得，用于接收模板消息推送（主通道）。
     * 获取方式：
     * 1. 公众号后台「用户管理」查看
     * 2. 微信测试号（https://mp.weixin.qq.com/debug/cgi-bin/sandboxinfo）扫码后显示
     */
    private String wechatOpenId;

    /** 微信推送 SendKey（Server酱，降级备用通道，用户自己填） */
    private String wechatSendKey;

    /** 账户状态 */
    private UserStatus status;

    /** 注册时间 */
    private LocalDateTime createTime;

    /** 最后登录时间 */
    private LocalDateTime lastLoginTime;

    /** 是否正在运行自动交易 */
    private boolean autoTraderRunning;

    /**
     * 是否为超级管理员（superAdmin）
     * 超级管理员账户不可被其他用户修改/禁用/删除，密码只能本人改
     */
    private boolean superAdmin;

    public enum StrategyType {
        MEDIUM_LONG("中长期策略"),
        DAY_TRADE("短线做T策略"),
        SWAP_STRONG("换股策略(汰弱留强)"),
        AUCTION_LIMIT_UP("竞价封板策略"),
        CUSTOM("自定义策略");

        private final String desc;
        StrategyType(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }

    public enum UserStatus {
        ACTIVE("正常"),
        DISABLED("禁用");

        private final String desc;
        UserStatus(String desc) { this.desc = desc; }
        public String getDesc() { return desc; }
    }
}

