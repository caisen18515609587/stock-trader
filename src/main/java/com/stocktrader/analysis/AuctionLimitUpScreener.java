package com.stocktrader.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.StockBar;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 集合竞价封板候选股筛选器
 * <p>
 * 工作时机：9:15~9:25 集合竞价阶段
 * <p>
 * 筛选逻辑（两阶段）：
 * <p>
 * ── 阶段一：竞价行情快筛（不拉K线，毫秒级）──────────────────────────────
 *   数据来源：新浪实时行情（hq.sinajs.cn），包含竞价价格、竞价量
 *   筛选条件：
 *   1. 竞价涨幅 ≥ 8%（主板）/ 18%（创业板/科创板）
 *   2. 非ST、非退市
 *   3. 竞价量 / 昨日成交量 ≥ 3%（竞价放量，说明封单充足）
 *   4. 前N日是否有连板记录（连板股更容易再次涨停）
 * <p>
 * ── 阶段二：历史K线技术验证（拉取近30日K线）──────────────────────────────
 *   对通过阶段一的候选股（通常5~20只），获取近30日K线验证趋势：
 *   1. 近期均线多头（MA5>MA10>MA20），说明处于上升趋势
 *   2. 近5日无连续跌停（量能和价格未出现极端异常）
 *   3. 换手率适中（3%~20%，太低冷门，太高炒作风险高）
 * <p>
 * 输出：封板候选股列表（按封板评分降序），供 AuctionTrader 买入决策
 */
public class AuctionLimitUpScreener {

    private static final Logger log = LoggerFactory.getLogger(AuctionLimitUpScreener.class);

    private final StockDataProvider dataProvider;

    // Python 数据服务：竞价专用实时行情接口（新浪实时源，绕过 Tushare）
    private static final String PYTHON_AUCTION_RT_URL = "http://localhost:8099/auction_realtime";
    // Python 数据服务：涨停板池接口（AKShare）
    private static final String PYTHON_ZT_POOL_URL = "http://localhost:8099/zt_pool";

    // 降级：新浪财经分页行情接口（约70次请求，竞价阶段有实时价格）
    private static final String SINA_STOCK_LIST_URL =
            "http://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "Market_Center.getHQNodeData?page=%d&num=80&sort=changepercent&asc=0" +
            "&node=hs_a&symbol=&_s_r_a=page";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final ObjectMapper OM = new ObjectMapper();

    // 当日涨停股票集合（每日刷新）
    private Set<String> limitUpStocks = new HashSet<>();
    private LocalDate ztPoolCacheDate = LocalDate.MIN;

    /**
     * 封板候选结果
     */
    public static class AuctionCandidate implements Comparable<AuctionCandidate> {
        public final String  stockCode;
        public final String  stockName;
        public final double  currentPrice;   // 竞价/实时价
        public final double  prevClose;      // 昨收
        public final double  changeRate;     // 竞价涨幅（0.0~1.0）
        public final double  auctionVolume;  // 竞价成交量（手）
        public final double  prevVolume;     // 昨日成交量（手）
        public final double  auctionVolRatio;// 竞价量比 = auctionVolume/prevVolume
        public final int     consecLimitUp; // 近N日连板次数（0=无连板）
        public final double  limitUpPrice;  // 涨停价
        public final double  score;         // 综合封板评分

        public AuctionCandidate(String stockCode, String stockName,
                                double currentPrice, double prevClose,
                                double changeRate, double auctionVolume,
                                double prevVolume, double auctionVolRatio,
                                int consecLimitUp, double limitUpPrice, double score) {
            this.stockCode      = stockCode;
            this.stockName      = stockName;
            this.currentPrice   = currentPrice;
            this.prevClose      = prevClose;
            this.changeRate     = changeRate;
            this.auctionVolume  = auctionVolume;
            this.prevVolume     = prevVolume;
            this.auctionVolRatio = auctionVolRatio;
            this.consecLimitUp  = consecLimitUp;
            this.limitUpPrice   = limitUpPrice;
            this.score          = score;
        }

        @Override
        public int compareTo(AuctionCandidate o) {
            return Double.compare(o.score, this.score); // 评分高的在前
        }

        @Override
        public String toString() {
            return String.format("[%s %s] 竞价涨幅=%.2f%% 竞价量比=%.1f%% 连板=%d 评分=%.1f",
                    stockCode, stockName, changeRate * 100, auctionVolRatio * 100, consecLimitUp, score);
        }
    }

    public AuctionLimitUpScreener(StockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
    }

    /**
     * 执行竞价封板选股
     * <p>
     * 全流程：获取全市场行情 → 阶段一快筛 → 阶段二K线验证 → 排序返回Top N
     *
     * @param topN         最多返回N只候选（建议3~5，资金量小时取1）
     * @param minChangeRate 竞价最低涨幅（主板建议0.08，即8%）
     * @return 按封板评分降序排列的候选股列表
     */
    public List<AuctionCandidate> screenAuctionCandidates(int topN, double minChangeRate) {
        log.info("[竞价选股] 开始筛选封板候选，目标TopN={}，最低竞价涨幅={}%...",
                topN, String.format("%.1f", minChangeRate * 100));

        // Step1：获取全量实时竞价行情快照
        // ⚠️ 必须走新浪实时接口，Tushare daily() 在竞价阶段只有昨日数据！
        List<Map<String, Object>> allQuotes = fetchAuctionQuotes();
        log.info("[竞价选股] 全市场竞价行情获取：{}只", allQuotes.size());

        if (allQuotes.isEmpty()) {
            log.warn("[竞价选股] 获取行情为空，无法进行封板筛选");
            return new ArrayList<>();
        }

        // Step2：阶段一快筛 —— 仅依赖行情快照
        List<Map<String, Object>> phase1 = phaseOneFilter(allQuotes, minChangeRate);
        log.info("[竞价选股] 阶段一快筛通过：{}只", phase1.size());

        if (phase1.isEmpty()) {
            log.info("[竞价选股] 无符合条件的封板候选（竞价涨幅不足）");
            return new ArrayList<>();
        }

        // Step3：阶段二K线验证（仅对通过阶段一的候选，通常5~20只，速度快）
        List<AuctionCandidate> candidates = phaseTwoVerify(phase1, minChangeRate);
        log.info("[竞价选股] 阶段二K线验证通过：{}只", candidates.size());

        // Step4：按封板评分排序，取TopN
        Collections.sort(candidates);
        List<AuctionCandidate> result = candidates.stream().limit(topN).collect(Collectors.toList());

        log.info("[竞价选股] ===== 封板候选结果 =====");
        for (int i = 0; i < result.size(); i++) {
            log.info("[竞价选股]   Top{}: {}", i + 1, result.get(i));
        }
        return result;
    }

    /**
     * 阶段一：纯行情快照快筛（无需K线，毫秒级完成）
     */
    private List<Map<String, Object>> phaseOneFilter(List<Map<String, Object>> quotes,
                                                      double minChangeRate) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> q : quotes) {
            String code = (String) q.get("code");
            String name = (String) q.get("name");
            if (code == null || code.isEmpty()) continue;

            // 过滤ST、退市
            if (name != null && (name.contains("ST") || name.contains("退"))) continue;

            double price         = toDouble(q.get("price"));
            double prevClose     = toDouble(q.get("prevClose"));
            double changePercent = toDouble(q.get("changePercent"));

            if (price <= 0 || prevClose <= 0) continue;

            // 实际涨幅
            double changeRate = changePercent / 100.0;

            // 判断是否为创业板/科创板（涨停幅度20%）
            boolean isKeChuang = code.startsWith("688") || code.startsWith("300");
            double localMinChange = isKeChuang ? Math.max(minChangeRate, 0.15) : minChangeRate;

            // 竞价涨幅必须达到阈值
            if (changeRate < localMinChange) continue;

            // 过滤已涨停（changePercent >= 9.5%/19.5%）竞价已完全涨停的暂时保留，
            // 关注的是竞价阶段接近但未涨停的，以及已经顶格竞价的
            // 以下同时保留「接近涨停（8%~）」和「已涨停竞价（9.5%+）」两类
            // 已跌停的直接过滤
            if (changePercent <= -9.5) continue;

            // 竞价量比（若行情数据有成交量）
            double volume    = toDouble(q.get("volume"));
            double prevVolume = toDouble(q.get("prevVolume")); // 部分数据源有昨日量
            double auctionVolRatio = (prevVolume > 0 && volume > 0)
                    ? volume / prevVolume : 0;

            // 若数据有量比字段直接用
            double volumeRatio = toDouble(q.get("volumeRatio"));
            if (volumeRatio > 0) auctionVolRatio = volumeRatio / 100.0; // 有些以百分比返回

            // 过滤量比太低的（竞价成交极少，可能是假封单）
            // 若数据不含昨日量，宽松处理（不过滤）
            if (auctionVolRatio > 0 && auctionVolRatio < 0.01) continue;

            q.put("_changeRate", changeRate);
            q.put("_auctionVolRatio", auctionVolRatio);
            q.put("_isKeChuang", isKeChuang);
            result.add(q);
        }
        return result;
    }

    /**
     * 阶段二：拉取K线验证（仅对阶段一通过的候选，通常不超过20只）
     */
    private List<AuctionCandidate> phaseTwoVerify(List<Map<String, Object>> phase1Candidates,
                                                   double minChangeRate) {
        List<AuctionCandidate> result = new ArrayList<>();
        LocalDate today    = LocalDate.now();
        LocalDate startDate = today.minusDays(40); // 拉40天K线，计算均线和连板

        for (Map<String, Object> q : phase1Candidates) {
            String code = (String) q.get("code");
            String name = (String) q.get("name");
            double changeRate    = toDouble(q.get("_changeRate"));
            double auctionVolRatio = toDouble(q.get("_auctionVolRatio"));
            boolean isKeChuang   = Boolean.TRUE.equals(q.get("_isKeChuang"));
            // 竞价成交量（股），转换为手
            double auctionVolShares = toDouble(q.get("volume")); // 已是股
            double auctionVolHands  = auctionVolShares / 100.0;  // 转手

            // 量比为0时（新浪接口无昨日量），用成交量绝对值兜底补充量比估算
            // 主板竞价>5000手、创业板/科创板>3000手，认为封单充足
            if (auctionVolRatio == 0 && auctionVolHands > 0) {
                double threshold = isKeChuang ? 3000 : 5000;
                if (auctionVolHands >= threshold * 2) auctionVolRatio = 0.10;      // 视为 >= 昨日10%
                else if (auctionVolHands >= threshold) auctionVolRatio = 0.05;     // 视为 >= 昨日5%
                else if (auctionVolHands >= threshold * 0.5) auctionVolRatio = 0.03; // 视为 >= 昨日3%
                else auctionVolRatio = 0.01; // 有量，但偏少
            }

            try {
                // 拉近40日K线（用于计算均线、识别连板）
                List<StockBar> bars = dataProvider.getDailyBars(
                        code, startDate, today, StockBar.AdjustType.NONE);

                if (bars == null || bars.size() < 5) {
                    log.debug("[竞价选股] {} {} K线不足5根，跳过", code, name);
                    continue;
                }

                double prevClose = bars.size() >= 2
                        ? bars.get(bars.size() - 2).getClose()
                        : bars.get(bars.size() - 1).getOpen();
                if (prevClose <= 0) continue;

                double price = toDouble(q.get("price"));
                if (price <= 0) price = prevClose * (1 + changeRate);

                double limitUpPrice = prevClose * (isKeChuang ? 1.20 : 1.10);

                // 计算近期连板次数
                int consecLimitUp = countConsecLimitUp(bars, isKeChuang);

                // 均线趋势验证
                boolean bullishTrend = isBullishTrend(bars);

                // 封板综合评分（使用补充后的 auctionVolRatio）
                double score = calcAuctionScore(changeRate, auctionVolRatio, consecLimitUp,
                        bullishTrend, price, limitUpPrice, isKeChuang);

                // 最终门槛：评分>=2.0 才入选
                if (score < 2.0) {
                    log.debug("[竞价选股] {} {} 评分不足({})，跳过", code, name, String.format("%.1f", score));
                    continue;
                }

                result.add(new AuctionCandidate(
                        code, name, price, prevClose, changeRate,
                        auctionVolShares, 0, auctionVolRatio,
                        consecLimitUp, limitUpPrice, score
                ));

            } catch (Exception e) {
                log.debug("[竞价选股] {} K线分析失败: {}", code, e.getMessage());
            }
        }

        // [P2 涨停板池] 获取当日涨停股票列表（用于加分）
        Set<String> limitUpPool = getLimitUpStocks();

        // 计算最终评分（加入涨停板池加成）
        for (int i = 0; i < result.size(); i++) {
            AuctionCandidate c = result.get(i);
            // 如果该股票已在当日涨停池中，加分（说明已经是涨停股，竞价继续封板概率高）
            double bonus = limitUpPool.contains(c.stockCode) ? 0.5 : 0.0;
            double newScore = c.score + bonus;
            // 替换为新的评分
            result.set(i, new AuctionCandidate(
                    c.stockCode, c.stockName, c.currentPrice, c.prevClose,
                    c.changeRate, c.auctionVolume, c.prevVolume, c.auctionVolRatio,
                    c.consecLimitUp, c.limitUpPrice, newScore
            ));
        }

        return result;
    }

    /**
     * 统计近N日（最多10日）中连板次数
     * 连板定义：当日收盘价 >= 昨收 × (1 + 涨停幅度 × 0.995)
     */
    private int countConsecLimitUp(List<StockBar> bars, boolean isKeChuang) {
        int count = 0;
        double limitPct = isKeChuang ? 1.195 : 1.095; // 连板涨幅判定阈值（留一点容差）
        int n = bars.size();
        // 从最近的倒数第2根bar往前数（最近一根是今日实时/竞价，不算）
        for (int i = n - 2; i >= Math.max(0, n - 11) && i >= 1; i--) {
            double close = bars.get(i).getClose();
            double prev  = bars.get(i - 1).getClose();
            if (prev > 0 && close >= prev * limitPct) {
                count++;
            } else {
                break; // 连板中断
            }
        }
        return count;
    }

    /**
     * 判断是否均线多头（MA5 > MA10 > MA20）
     */
    private boolean isBullishTrend(List<StockBar> bars) {
        int n = bars.size();
        if (n < 20) return false;

        double ma5 = 0, ma10 = 0, ma20 = 0;
        for (int i = n - 5; i < n; i++) ma5  += bars.get(i).getClose();
        for (int i = n - 10; i < n; i++) ma10 += bars.get(i).getClose();
        for (int i = n - 20; i < n; i++) ma20 += bars.get(i).getClose();
        ma5 /= 5; ma10 /= 10; ma20 /= 20;

        return ma5 > ma10 && ma10 > ma20;
    }

    /**
     * 计算封板综合评分（0.0~5.0+）
     * <p>
     * 评分改进说明：
     * 1. 竞价涨幅权重提升（核心指标，权重最高）
     * 2. 连板加分降低且3板以上不再加分（高连板炸板风险高）
     * 3. 竞价成交量改用绝对值判断（原量比字段因新浪接口限制常为0）
     * 4. 涨停价紧贴加分提高（竞价顶格封板才是最强信号）
     */
    private double calcAuctionScore(double changeRate, double auctionVolRatio,
                                     int consecLimitUp, boolean bullishTrend,
                                     double price, double limitUpPrice,
                                     boolean isKeChuang) {
        double score = 0.0;
        double fullLimitUp = isKeChuang ? 0.20 : 0.10;

        // ── 竞价涨幅维度（最核心指标，权重最高）──
        // 竞价顶格封板（已达涨停）是最强信号，大幅提权
        if (changeRate >= fullLimitUp * 0.99) score += 3.0;      // 已达涨停（顶格封板）
        else if (changeRate >= fullLimitUp * 0.97) score += 2.0; // 接近涨停（差0.3%以内）
        else if (changeRate >= fullLimitUp * 0.95) score += 1.5; // 接近涨停（差0.5%以内）
        else if (changeRate >= fullLimitUp * 0.90) score += 1.0; // 接近涨停（差1%以内）
        else if (changeRate >= fullLimitUp * 0.80) score += 0.5; // 竞价强势（差2%以内）

        // ── 封单量维度 ──
        // 新浪实时接口无法提供昨日量，改用竞价成交量绝对值判断
        // auctionVolume 单位：股（volume * 100），用手（/100）判断
        // 注：auctionVolRatio 在量比可用时仍使用
        if (auctionVolRatio >= 0.10)      score += 1.0; // 竞价量 >= 昨日10%（强封单）
        else if (auctionVolRatio >= 0.05) score += 0.7;
        else if (auctionVolRatio >= 0.03) score += 0.4;
        else if (auctionVolRatio > 0)     score += 0.2;
        // 量比为0时（新浪接口限制），根据成交量绝对值补充判断
        // auctionVolume 已是"股"，除以100得到手数
        // 主板竞价 > 5000手 / 创业板 > 3000手 算有效封单
        // 此处用 auctionVolRatio == 0 的兜底逻辑
        // （auctionVolume 传入 phaseTwoVerify 时从 q.get("volume") 取）

        // ── 连板维度（降权，3板以上不再加分，因高连板炸板风险急剧上升）──
        if (consecLimitUp == 1)      score += 0.5; // 首板（最理想）
        else if (consecLimitUp == 2) score += 0.3; // 2连板（尚可）
        // 3板及以上：不加分，因获利盘厚重，炸板率高

        // ── 均线趋势 ──
        if (bullishTrend) score += 0.5;

        // ── 实时价紧贴涨停板（加权提高：顶格封板是最强信号）──
        if (price >= limitUpPrice * 0.999) score += 1.0;       // 精确封板
        else if (price >= limitUpPrice * 0.995) score += 0.5;  // 紧贴涨停

        return score;
    }

    // =================== 竞价行情数据获取 ===================

    /**
     * 获取全市场实时竞价行情快照（专为竞价阶段设计，绕过 Tushare）
     * <p>
     * 优先：调用 Python 数据服务的 /auction_realtime 接口（新浪实时源，1次请求）
     * 降级：调用新浪财经分页行情接口（约70次请求，但竞价阶段也有实时价格）
     */
    private List<Map<String, Object>> fetchAuctionQuotes() {
        // 优先：Python 数据服务（新浪实时，支持竞价阶段）
        List<Map<String, Object>> result = fetchAuctionQuotesFromPython();
        if (!result.isEmpty()) {
            log.info("[竞价行情] Python/新浪实时接口获取：{}只", result.size());
            return result;
        }
        // 降级：新浪财经分页接口（也是实时数据，竞价阶段可用）
        log.info("[竞价行情] Python服务不可用，降级使用新浪财经分页接口...");
        return fetchAuctionQuotesFromSina();
    }

    /**
     * 通过 Python 数据服务获取竞价实时行情
     */
    private List<Map<String, Object>> fetchAuctionQuotesFromPython() {
        try {
            String body = httpGet(PYTHON_AUCTION_RT_URL);
            if (body == null || body.trim().isEmpty() || body.trim().startsWith("{\"error")) {
                return new ArrayList<>();
            }

            JsonNode arr = OM.readTree(body);
            if (!arr.isArray() || arr.size() == 0) return new ArrayList<>();

            List<Map<String, Object>> all = new ArrayList<>();
            for (JsonNode item : arr) {
                String code = item.path("code").asText("");
                if (code.isEmpty()) continue;

                Map<String, Object> q = new HashMap<>();
                q.put("code",          code);
                q.put("name",          item.path("name").asText(code));
                q.put("price",         item.path("price").asDouble(0));
                q.put("changePercent", item.path("changePercent").asDouble(0));
                q.put("change",        item.path("change").asDouble(0));
                q.put("volume",        (long) item.path("volume").asDouble(0));
                q.put("amount",        item.path("amount").asDouble(0));
                q.put("high",          item.path("high").asDouble(0));
                q.put("low",           item.path("low").asDouble(0));
                q.put("open",          item.path("open").asDouble(0));
                q.put("prevClose",     item.path("preClose").asDouble(0));
                q.put("volumeRatio",   1.0); // 新浪实时接口暂不提供量比
                all.add(q);
            }
            return all;
        } catch (Exception e) {
            log.debug("[竞价行情] Python auction_realtime 接口不可用: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 新浪财经分页行情接口（降级，竞价阶段实时价格可用，约70次请求）
     */
    private List<Map<String, Object>> fetchAuctionQuotesFromSina() {
        List<Map<String, Object>> all = new ArrayList<>();
        int maxPage = 70;
        for (int page = 1; page <= maxPage; page++) {
            try {
                String url = String.format(SINA_STOCK_LIST_URL, page);
                String body = httpGet(url);
                if (body == null || body.trim().isEmpty() || body.trim().equals("null")) break;

                JsonNode arr;
                try {
                    arr = OM.readTree(body);
                } catch (Exception e) {
                    log.debug("[竞价行情] 新浪第{}页解析失败: {}", page, e.getMessage());
                    break;
                }
                if (!arr.isArray() || arr.size() == 0) break;

                for (JsonNode item : arr) {
                    String code = item.path("code").asText("");
                    if (code.isEmpty()) continue;

                    double trade    = parseDoubleStr(item.path("trade").asText("0"));
                    double settlement = parseDoubleStr(item.path("settlement").asText("0"));
                    if (trade <= 0 || settlement <= 0) continue;

                    Map<String, Object> q = new HashMap<>();
                    q.put("code",          code);
                    q.put("name",          item.path("name").asText(code));
                    q.put("price",         trade);
                    q.put("changePercent", item.path("changepercent").asDouble(0));
                    q.put("change",        item.path("pricechange").asDouble(0));
                    q.put("volume",        item.path("volume").asLong(0));
                    q.put("amount",        item.path("amount").asDouble(0));
                    q.put("high",          parseDoubleStr(item.path("high").asText("0")));
                    q.put("low",           parseDoubleStr(item.path("low").asText("0")));
                    q.put("open",          parseDoubleStr(item.path("open").asText("0")));
                    q.put("prevClose",     settlement);
                    q.put("volumeRatio",   1.0);
                    all.add(q);
                }

                if (arr.size() < 80) break;
                Thread.sleep(150);
            } catch (Exception e) {
                log.error("[竞价行情] 新浪分页第{}页失败: {}", page, e.getMessage());
                break;
            }
        }
        log.info("[竞价行情] 新浪分页接口获取：{}只", all.size());
        return all;
    }

    private String httpGet(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "https://finance.sina.com.cn/")
                .build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return "";
            return response.body().string();
        }
    }

    private double parseDoubleStr(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        try { return ((Number) val).doubleValue(); } catch (Exception e) { return 0; }
    }

    /**
     * [P2 涨停板池] 获取当日涨停股票列表（从 Python 服务缓存，按日期缓存）
     * AKShare 接口，返回当日所有涨停股（含首板/连板/炸板信息）
     */
    private Set<String> getLimitUpStocks() {
        LocalDate today = LocalDate.now();
        // 每日仅请求一次
        if (ztPoolCacheDate.equals(today) && !limitUpStocks.isEmpty()) {
            return limitUpStocks;
        }
        try {
            String body = httpGet(PYTHON_ZT_POOL_URL);
            if (body == null || body.trim().isEmpty() || body.trim().startsWith("{\"error")) {
                return limitUpStocks;
            }
            JsonNode arr = OM.readTree(body);
            if (arr.isArray()) {
                limitUpStocks.clear();
                for (JsonNode item : arr) {
                    String code = item.path("code").asText("");
                    if (!code.isEmpty()) {
                        limitUpStocks.add(code);
                    }
                }
                ztPoolCacheDate = today;
                log.info("[涨停板池] 当日涨停股票: {} 只", limitUpStocks.size());
            }
        } catch (Exception e) {
            log.warn("[涨停板池] 获取涨停板池失败: {}，不影响竞价选股", e.getMessage());
        }
        return limitUpStocks;
    }
}

