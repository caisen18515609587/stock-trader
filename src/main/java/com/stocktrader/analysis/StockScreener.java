package com.stocktrader.analysis;

import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.model.AnalysisResult;
import com.stocktrader.model.FundamentalFactor;
import com.stocktrader.model.StockBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 全市场选股引擎
 * <p>
 * 流程：
 * 1. 从东方财富获取全量A股列表（含实时涨跌幅、换手率、市值等）
 * 2. 预筛选：剔除ST、停牌、涨停板追高、市值过小等异常股
 * 3. 对预筛选通过的股票获取历史K线，计算技术指标综合评分
 * 4. 按评分排序，返回Top N
 */
public class StockScreener {

    private static final Logger log = LoggerFactory.getLogger(StockScreener.class);

    private final StockDataProvider dataProvider;
    private final StockAnalyzer analyzer;
    private final FundamentalDataProvider fundamentalProvider;

    // 复用 OkHttpClient（避免每次请求重建）
    private static final okhttp3.OkHttpClient HTTP_CLIENT = new okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    // 新浪财经沪深A股行情列表接口（稳定，支持分页，含涨跌幅/换手率/PE/市值等）
    // sort=changepercent asc=0 按涨幅倒序，node=hs_a 沪深A股
    private static final String SINA_STOCK_LIST_URL =
            "http://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
            "Market_Center.getHQNodeData?page=%d&num=80&sort=changepercent&asc=0" +
            "&node=hs_a&symbol=&_s_r_a=page";

    // Python Tushare 数据服务：一次请求获取全市场行情（仅消耗 1 次调用频率）
    private static final String PYTHON_MARKET_DAILY_URL = "http://localhost:8099/market_daily";
    // Python 停牌股票列表接口
    private static final String PYTHON_SUSPEND_URL = "http://localhost:8099/suspend";
    // Python 个股新闻接口（含情感分析）
    private static final String PYTHON_STOCK_NEWS_URL = "http://localhost:8099/stock_news";
    // [P1-1] 东方财富个股资金流向接口（免费公开）
    // secid=市场代码.股票代码（1=沪市 0=深市），返回当日主力净流入数据
    private static final String EMC_MONEY_FLOW_URL = "https://push2.eastmoney.com/api/qt/stock/fflow/kline/get";

    // 当日停牌股票集合（每日刷新，Set 便于 O(1) 查询）
    private Set<String> suspendedStocks = new HashSet<>();
    private LocalDate suspendCacheDate = LocalDate.MIN;

    // 个股新闻情感缓存（代码 -> 情感得分：+1正面/0中性/-1负面）
    private Map<String, Integer> stockSentiment = new HashMap<>();
    private LocalDate sentimentCacheDate = LocalDate.MIN;

    public StockScreener(StockDataProvider dataProvider) {
        this.dataProvider = dataProvider;
        this.analyzer = new StockAnalyzer();
        this.fundamentalProvider = new FundamentalDataProvider();
    }

    /**
     * 获取当日停牌股票列表（从 Python 服务缓存，按日期缓存避免重复请求）
     */
    private Set<String> getSuspendedStocks() {
        LocalDate today = LocalDate.now();
        // 每日仅请求一次
        if (suspendCacheDate.equals(today) && !suspendedStocks.isEmpty()) {
            return suspendedStocks;
        }
        try {
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(PYTHON_SUSPEND_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (okhttp3.Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String json = resp.body().string();
                    com.fasterxml.jackson.databind.JsonNode root =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                    if (root.isArray()) {
                        suspendedStocks.clear();
                        for (com.fasterxml.jackson.databind.JsonNode item : root) {
                            String code = item.path("code").asText("");
                            if (!code.isEmpty()) {
                                suspendedStocks.add(code);
                            }
                        }
                        suspendCacheDate = today;
                        log.info("[停牌封控] 当日停牌股票: {} 只", suspendedStocks.size());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[停牌封控] 获取停牌列表失败: {}，不停牌过滤", e.getMessage());
        }
        return suspendedStocks;
    }

    /**
     * [P3 新闻情感] 获取个股新闻情感得分（从 Python 服务缓存，按日期缓存）
     * 返回: +1 正面, 0 中性, -1 负面
     * 情感来源于新闻标题关键词分析
     */
    private int getStockSentiment(String code) {
        LocalDate today = LocalDate.now();
        // 每日仅请求一次
        if (sentimentCacheDate.equals(today) && stockSentiment.containsKey(code)) {
            return stockSentiment.get(code);
        }
        // 批量获取多只股票情感（这里简化为单只查询）
        try {
            String url = PYTHON_STOCK_NEWS_URL + "?code=" + code;
            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();
            try (okhttp3.Response resp = HTTP_CLIENT.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String json = resp.body().string();
                    com.fasterxml.jackson.databind.JsonNode root =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                    if (root.isArray() && root.size() > 0) {
                        int positive = 0, negative = 0;
                        for (com.fasterxml.jackson.databind.JsonNode item : root) {
                            String sentiment = item.path("sentiment").asText("");
                            if ("positive".equals(sentiment)) positive++;
                            else if ("negative".equals(sentiment)) negative++;
                        }
                        int score = 0;
                        if (positive > negative) score = 1;
                        else if (negative > positive) score = -1;
                        // 缓存结果
                        if (!sentimentCacheDate.equals(today)) {
                            sentimentCacheDate = today;
                            stockSentiment.clear();
                        }
                        stockSentiment.put(code, score);
                        log.debug("[新闻情感] {} 情感得分: {} (正{}条 负{}条)", code, score, positive, negative);
                        return score;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[新闻情感] 获取 {} 新闻失败: {}", code, e.getMessage());
        }
        // 默认中性
        stockSentiment.put(code, 0);
        return 0;
    }

    /**
     * 选股结果
     */
    public static class ScreenResult implements Comparable<ScreenResult> {
        public final String stockCode;
        public final String stockName;
        public final double currentPrice;
        public final double changePercent;   // 今日涨跌幅
        public final double turnoverRate;    // 换手率
        public final double pe;              // 市盈率
        public final double totalMarketCap;  // 总市值（亿元）
        public final int techScore;          // 技术面评分（0~100）
        public final int preScore;           // 预筛选综合分
        public final String trend;
        public final String recommendation;
        public final AnalysisResult analysis;
        /**
         * [P1-2 优化] 选股时拉取的K线数据，供当次扫描循环的 scanStock() 直接复用，避免重复请求。
         * 注意：仅在同一轮扫描内有效，跨轮扫描时应重新拉取最新K线。
         */
        public final List<StockBar> bars;

        /** 完整构造（含bars）—— 选股时使用 */
        public ScreenResult(String stockCode, String stockName, double currentPrice,
                            double changePercent, double turnoverRate, double pe,
                            double totalMarketCap, int techScore, int preScore,
                            String trend, String recommendation, AnalysisResult analysis,
                            List<StockBar> bars) {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.currentPrice = currentPrice;
            this.changePercent = changePercent;
            this.turnoverRate = turnoverRate;
            this.pe = pe;
            this.totalMarketCap = totalMarketCap;
            this.techScore = techScore;
            this.preScore = preScore;
            this.trend = trend;
            this.recommendation = recommendation;
            this.analysis = analysis;
            this.bars = bars;
        }

        /** 兼容构造（不含bars）—— 展示等无需K线数据的场景使用 */
        public ScreenResult(String stockCode, String stockName, double currentPrice,
                            double changePercent, double turnoverRate, double pe,
                            double totalMarketCap, int techScore, int preScore,
                            String trend, String recommendation, AnalysisResult analysis) {
            this(stockCode, stockName, currentPrice, changePercent, turnoverRate, pe,
                    totalMarketCap, techScore, preScore, trend, recommendation, analysis, null);
        }

        @Override
        public int compareTo(ScreenResult o) {
            // 先按技术评分，再按换手率
            int cmp = Integer.compare(o.techScore, this.techScore);
            if (cmp != 0) return cmp;
            return Double.compare(o.turnoverRate, this.turnoverRate);
        }

        public void print() {
            System.out.printf("  %-8s %-8s 现价%7.2f 涨跌%+5.2f%% 换手%5.2f%% PE%6.1f " +
                            "市值%7.1f亿 评分%3d 趋势:%s 建议:%s%n",
                    stockCode, stockName, currentPrice, changePercent,
                    turnoverRate, pe, totalMarketCap, techScore, trend, recommendation);
        }
    }

    /**
     * 全市场扫描选股（主入口）
     *
     * @param topN     选出前N支
     * @param minScore 最低技术评分阈值（建议60以上）
     * @return 排序后的Top N选股结果
     */
    public List<ScreenResult> screenTopStocks(int topN, int minScore) {
        return screenTopStocks(topN, minScore, 0);
    }

    /**
     * 全市场扫描选股（含资金过滤）
     *
     * @param topN              选出前N支
     * @param minScore          最低技术评分阈值（建议60以上）
     * @param maxAffordablePrice 账户可承受的最高股价（= availableCash / 100），0 表示不过滤
     *                          用于小资金账户过滤掉买不起的高价股（如贵州茅台1500+元）
     * @return 排序后的Top N选股结果
     */
    public List<ScreenResult> screenTopStocks(int topN, int minScore, double maxAffordablePrice) {
        log.info("开始全市场选股扫描，目标选出Top{}只（最低评分{}{}）...",
                topN, minScore,
                maxAffordablePrice > 0 ? String.format(" 股价上限%.0f元", maxAffordablePrice) : "");

        // Step1: 从东方财富获取全量实时行情快照（分页）
        List<Map<String, Object>> allQuotes = fetchAllStockQuotes();
        log.info("获取全量A股行情：{}只", allQuotes.size());

        // Step2: 预筛选（快速过滤，不需要K线数据）
        List<Map<String, Object>> candidates = preFilter(allQuotes, maxAffordablePrice);
        log.info("预筛选后候选股票：{}只", candidates.size());

        // [P1-1] Step2.5: 主力资金流入验证（剔除主力大幅流出股）
        candidates = filterByMoneyFlow(candidates);
        log.info("[P1-1] 资金流向过滤后候选股票：{}只", candidates.size());

        if (candidates.isEmpty()) {
            log.warn("预筛选无候选股票，放宽条件降级重试...");
            // 降级：调用无价格限制版本（仅ST/停牌过滤），再空则兜底取全量
            candidates = preFilter(allQuotes, 0);
            if (candidates.isEmpty()) {
                log.warn("降级预筛选仍无结果，兜底取全量行情（{}只）", allQuotes.size());
                candidates = allQuotes;
            }
        }

        // Step3: 对候选股票并行获取K线并做技术分析
        List<ScreenResult> results = analyzeWithKline(candidates, minScore);
        log.info("技术分析完成，符合条件（评分>={})：{}只", minScore, results.size());

        // [P3-1 ROE硬过滤] 技术面分析完成后，对候选股做轻量ROE质量门槛过滤
        // 即使在纯技术面模式（screen.fundamental.enabled=false）下也执行，
        // 确保选出的股票具备基本的盈利能力（ROE>=roeMin），排除持续亏损或低质量股。
        // 安全降级：当ROE数据为0（积分不足/数据缺失）时不过滤，防止误杀。
        double roeMin = com.stocktrader.config.SystemConfig.getInstance().getFundamentalRoeMin();
        if (roeMin > 0 && !results.isEmpty()) {
            results = applyRoeHardFilter(results, roeMin);
        }

        // Step4: 排序，取Top N
        Collections.sort(results);
        List<ScreenResult> topList = results.stream().limit(topN).collect(Collectors.toList());

        log.info("====== 选股完成，Top{} ======", topN);
        for (int i = 0; i < topList.size(); i++) {
            ScreenResult r = topList.get(i);
            log.info("  第{}名: {} {} 评分{}", i + 1, r.stockCode, r.stockName, r.techScore);
        }
        return topList;
    }

    /**
     * 港股全市场扫描选股
     * <p>
     * 流程：
     * 1. 从东方财富获取港股（港交所主板）实时行情快照
     * 2. 预筛选：剔除停牌、异常股
     * 3. 对候选股获取历史K线，计算技术指标综合评分
     * 4. 按评分排序，返回Top N
     *
     * @param topN     选出前N支
     * @param minScore 最低技术评分阈值（建议55以上，港股波动较大）
     * @return 排序后的Top N港股选股结果
     */
    public List<ScreenResult> screenTopHkStocks(int topN, int minScore) {
        log.info("[港股选股] 开始港股全市场扫描，目标选出Top{}只（最低评分{}）...", topN, minScore);

        // Step1: 获取港股实时行情快照（东方财富港交所主板）
        List<Map<String, Object>> allQuotes = fetchAllHkStockQuotes();
        log.info("[港股选股] 获取港股行情：{}只", allQuotes.size());

        if (allQuotes.isEmpty()) {
            log.warn("[港股选股] 港股行情获取失败，返回空结果");
            return new ArrayList<>();
        }

        // Step2: 预筛选（剔除停牌、低流动性、涨跌幅异常股）
        List<Map<String, Object>> candidates = preFilterHk(allQuotes);
        log.info("[港股选股] 预筛选后候选股票：{}只", candidates.size());

        if (candidates.isEmpty()) {
            log.warn("[港股选股] 预筛选无候选，降级取全量");
            candidates = allQuotes;
        }

        // Step3: 并行获取K线并做技术分析
        List<ScreenResult> results = analyzeWithKline(candidates, minScore);
        log.info("[港股选股] 技术分析完成，符合条件（评分>={}）：{}只", minScore, results.size());

        // Step4: 排序，取Top N
        Collections.sort(results);
        List<ScreenResult> topList = results.stream().limit(topN).collect(Collectors.toList());

        log.info("====== [港股选股] 完成，Top{} ======", topN);
        for (int i = 0; i < topList.size(); i++) {
            ScreenResult r = topList.get(i);
            log.info("  第{}名: {} {} 评分{}", i + 1, r.stockCode, r.stockName, r.techScore);
        }
        return topList;
    }

    // 港股蓝筹候选池（按市值/流动性排序，当东方财富全量接口不可用时使用）
    private static final List<String> HK_BLUECHIP_POOL = Arrays.asList(
        "00700", "00005", "00939", "01398", "00941", "01299",
        "00388", "09988", "03690", "00883", "00011", "00002",
        "01997", "00003", "02628", "02318", "00016", "01177",
        "01810", "00669", "00027", "02382", "06862", "01066"
    );

    /**
     * 从东方财富获取港股（港交所主板）全量实时行情快照
     * 市场代码：m:128+t:3（主板），m:128+t:4（创业板）
     * 当东方财富接口不可用时，降级为港股蓝筹固定池+新浪实时行情
     */
    private List<Map<String, Object>> fetchAllHkStockQuotes() {
        // ===== 方案1：东方财富港股行情列表接口（全量）=====
        String url = "https://push2.eastmoney.com/api/qt/clist/get?" +
                "pn=1&pz=3000&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2" +
                "&fid=f3&fs=m:128+t:3,m:128+t:4" +
                "&fields=f12,f14,f2,f3,f4,f8,f9,f10,f15,f16,f17,f18,f20,f21" +
                "&_=" + System.currentTimeMillis();
        try {
            String body = httpGet(url);
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = om.readTree(body);
            com.fasterxml.jackson.databind.JsonNode items = root.path("data").path("diff");
            if (!items.isArray()) throw new Exception("港股行情数据格式异常");

            List<Map<String, Object>> all = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : items) {
                String code = item.path("f12").asText("");
                String name = item.path("f14").asText(code);
                if (code.isEmpty()) continue;

                double price         = item.path("f2").asDouble(0) / 1000.0;
                double changePercent = item.path("f3").asDouble(0) / 100.0;
                double change        = item.path("f4").asDouble(0) / 1000.0;
                double turnoverRate  = item.path("f8").asDouble(0) / 100.0;
                double pe            = item.path("f9").asDouble(0) / 100.0;
                double volumeRatio   = item.path("f10").asDouble(0) / 100.0;
                double high          = item.path("f15").asDouble(0) / 1000.0;
                double low           = item.path("f16").asDouble(0) / 1000.0;
                double open          = item.path("f17").asDouble(0) / 1000.0;
                double prevClose     = item.path("f18").asDouble(0) / 1000.0;
                double totalMarketCap = item.path("f20").asDouble(0) / 1e8;

                if (price <= 0) continue;

                Map<String, Object> q = new HashMap<>();
                q.put("code",           code);
                q.put("name",           name);
                q.put("price",          price);
                q.put("changePercent",  changePercent);
                q.put("change",         change);
                q.put("turnoverRate",   turnoverRate);
                q.put("pe",             pe);
                q.put("volumeRatio",    volumeRatio);
                q.put("high",           high);
                q.put("low",            low);
                q.put("open",           open);
                q.put("prevClose",      prevClose);
                q.put("totalMarketCap", totalMarketCap);
                q.put("market",         128);
                all.add(q);
            }
            if (!all.isEmpty()) {
                log.info("[港股选股] 东方财富港股行情获取成功，共{}只", all.size());
                return all;
            }
            throw new Exception("港股行情列表为空");
        } catch (Exception e) {
            log.warn("[港股选股] 东方财富接口不可用（{}），降级使用蓝筹固定池+新浪实时行情", e.getMessage());
        }

        // ===== 方案2：降级 - 蓝筹固定池 + 新浪实时行情 =====
        return fetchHkBluechipQuotesBySina();
    }

    /**
     * 降级方案：用新浪财经实时行情接口批量获取港股蓝筹行情快照
     * 新浪接口：https://hq.sinajs.cn/list=hk00700,hk00005,...
     * 数据格式：股票名称,买价,现价,最高,最低,开盘,成交量,成交额,涨跌幅,...
     */
    private List<Map<String, Object>> fetchHkBluechipQuotesBySina() {
        // 将代码列表转为新浪格式（hk00700,hk00005,...）
        String codeList = HK_BLUECHIP_POOL.stream()
                .map(c -> "hk" + String.format("%05d", Integer.parseInt(c)))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        if (codeList.isEmpty()) return new ArrayList<>();

        String sinaUrl = "https://hq.sinajs.cn/list=" + codeList;
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            String body = httpGet(sinaUrl);
            if (body == null || body.isEmpty()) return result;

            // 解析新浪港股行情（格式：var hq_str_hk00700="腾讯控股,491.2,495.6,...";）
            String[] lines = body.split("\n");
            for (int i = 0; i < lines.length && i < HK_BLUECHIP_POOL.size(); i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                // 提取代码：hq_str_hk00700 -> 00700
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String varName = line.substring(0, eq).trim();
                String codeRaw = varName.replaceAll("var\\s+hq_str_hk", "").trim();
                // 提取数据：去掉前后引号
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (q1 < 0 || q2 <= q1) continue;
                String data = line.substring(q1 + 1, q2);
                String[] parts = data.split(",");
                if (parts.length < 9) continue;

                try {
                    String name = parts[0];
                    double prevClose = parseDouble(parts[1]);
                    double price     = parseDouble(parts[2]);
                    double high      = parseDouble(parts[3]);
                    double low       = parseDouble(parts[4]);
                    double open      = parseDouble(parts[5]);
                    long   volume    = (long) parseDouble(parts[6]);
                    double amount    = parseDouble(parts[7]);
                    double changePct = prevClose > 0 ? (price - prevClose) / prevClose * 100.0 : 0;

                    if (price <= 0) continue;

                    Map<String, Object> q = new HashMap<>();
                    q.put("code",           codeRaw);
                    q.put("name",           name);
                    q.put("price",          price);
                    q.put("changePercent",  changePct);
                    q.put("change",         price - prevClose);
                    q.put("turnoverRate",   0.0);  // 新浪无换手率，设为0（预筛选不会因此过滤）
                    q.put("pe",             0.0);
                    q.put("volumeRatio",    1.0);
                    q.put("high",           high);
                    q.put("low",            low);
                    q.put("open",           open);
                    q.put("prevClose",      prevClose);
                    q.put("totalMarketCap", 100.0); // 蓝筹市值默认100亿+，不会被过滤
                    q.put("market",         116);   // 港股通
                    result.add(q);
                } catch (Exception ex) {
                    log.debug("[港股蓝筹] 解析{}行情失败: {}", codeRaw, ex.getMessage());
                }
            }
            log.info("[港股选股] 新浪蓝筹行情获取成功，共{}只", result.size());
        } catch (Exception e) {
            log.error("[港股选股] 新浪蓝筹行情获取失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 港股预筛选：剔除明显不适合的股票
     * 港股无涨跌停限制，ST机制与A股不同，过滤条件相对宽松
     */
    private List<Map<String, Object>> preFilterHk(List<Map<String, Object>> quotes) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> q : quotes) {
            String name  = (String) q.get("name");
            double price = toDouble(q.get("price"));
            double changePercent = toDouble(q.get("changePercent"));
            double totalMarketCap = toDouble(q.get("totalMarketCap"));

            // 剔除停牌（价格为0）
            if (price <= 0) continue;
            // 剔除仙股（价格 < 0.1 港元）
            if (price < 0.1) continue;
            // 剔除名称含"停牌"的股票
            if (name != null && (name.contains("停牌") || name.contains("-B") && name.contains("债"))) continue;
            // 剔除极端异常涨跌（±50%以上，可能是特殊停复牌）
            if (Math.abs(changePercent) > 50) continue;
            // 市值极小的股票流动性差（< 1亿港元）
            if (totalMarketCap > 0 && totalMarketCap < 1) continue;

            result.add(q);
        }
        return result;
    }

    /**
     * [P3-1] ROE 硬过滤：对技术面候选股批量查询ROE，排除低盈利能力股票
     * <p>
     * 设计原则：
     * 1. 仅当ROE数据有效（>0）时才执行过滤，数据为0（积分不足/接口异常）时放行（防误杀）。
     * 2. 批量查询，1次HTTP请求获取所有候选股ROE，不影响选股速度。
     * 3. 过滤门槛默认8%（低于ROE=8%的股票盈利能力弱，长期来看股价上涨动力不足）。
     *    注：配置项 screen.fundamental.roe.min 默认10%（可在 application.properties 中调整）。
     *
     * @param results 技术面候选股列表
     * @param roeMin  ROE最低阈值（%），如8.0表示8%
     * @return 过滤后的候选股列表
     */
    private List<ScreenResult> applyRoeHardFilter(List<ScreenResult> results, double roeMin) {
        try {
            List<String> codes = results.stream().map(r -> r.stockCode).collect(Collectors.toList());
            Map<String, String> nameMap = new LinkedHashMap<>();
            results.forEach(r -> nameMap.put(r.stockCode, r.stockName));

            // 批量查询基本面（ROE），1次HTTP请求
            Map<String, FundamentalFactor> fundMap =
                    fundamentalProvider.getBatchFundamental(codes, nameMap);

            List<ScreenResult> filtered = new ArrayList<>();
            int filteredCount = 0;
            for (ScreenResult r : results) {
                FundamentalFactor f = fundMap.get(r.stockCode);
                if (f != null && f.getRoe() > 0 && f.getRoe() < roeMin) {
                    // ROE数据有效且低于阈值，排除
                    log.debug("[P3-1 ROE过滤] {} {} ROE={}% < {}%，排除",
                            r.stockCode, r.stockName,
                            String.format("%.1f", f.getRoe()),
                            String.format("%.0f", roeMin));
                    filteredCount++;
                } else {
                    filtered.add(r);
                }
            }
            if (filteredCount > 0) {
                log.info("[P3-1 ROE过滤] ROE<{}% 排除{}只，剩余{}只候选",
                        String.format("%.0f", roeMin), filteredCount, filtered.size());
            } else {
                log.debug("[P3-1 ROE过滤] ROE<{}% 无股票被排除（候选{}只均达标或数据缺失）",
                        String.format("%.0f", roeMin), results.size());
            }
            return filtered;
        } catch (Exception e) {
            // 接口异常时安全降级：不过滤，返回原始列表
            log.warn("[P3-1 ROE过滤] 批量查询ROE失败，降级跳过过滤: {}", e.getMessage());
            return results;
        }
    }

    /**
     * 获取全量A股实时行情快照
     * 优先：调用本地 Python Tushare 服务的 /market_daily 接口（1次请求搞定全市场）
     * 降级：新浪财经分页接口（约70次请求）
     */
    public List<Map<String, Object>> fetchAllStockQuotes() {
        // 优先尝试 Python 数据服务（1次调用 = 全市场数据）
        List<Map<String, Object>> pythonResult = fetchAllStockQuotesFromPython();
        if (!pythonResult.isEmpty()) {
            log.info("Python数据服务获取全量A股行情：{}只（1次请求）", pythonResult.size());
            return pythonResult;
        }

        // 降级：新浪财经分页接口
        log.info("Python数据服务不可用，降级使用新浪财经分页接口...");
        return fetchAllStockQuotesFromSina();
    }

    /**
     * 从 Python Tushare 服务一次性获取全市场行情（仅消耗 1 次调用频率）
     */
    private List<Map<String, Object>> fetchAllStockQuotesFromPython() {
        try {
            String body = httpGet(PYTHON_MARKET_DAILY_URL);
            if (body == null || body.trim().isEmpty() || body.trim().startsWith("{\"error")) {
                return new ArrayList<>();
            }

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = om.readTree(body);
            if (!arr.isArray() || arr.size() == 0) return new ArrayList<>();

            List<Map<String, Object>> all = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                String code = item.path("code").asText("");
                if (code.isEmpty()) continue;

                Map<String, Object> q = new HashMap<>();
                q.put("code",           code);
                q.put("name",           item.path("name").asText(code));
                q.put("price",          item.path("price").asDouble(0));
                q.put("changePercent",  item.path("changePercent").asDouble(0));
                q.put("change",         item.path("change").asDouble(0));
                q.put("volume",         (long) item.path("volume").asDouble(0));
                q.put("amount",         item.path("amount").asDouble(0));
                q.put("high",           item.path("high").asDouble(0));
                q.put("low",            item.path("low").asDouble(0));
                q.put("open",           item.path("open").asDouble(0));
                q.put("prevClose",      item.path("preClose").asDouble(0));
                // 120积分时 Tushare 不提供换手率/PE/市值，置 0（preFilter 中对应条件不过滤）
                q.put("turnoverRate",   item.path("turnoverRate").asDouble(0));
                q.put("pe",             item.path("pe").asDouble(0));
                q.put("totalMarketCap", item.path("totalMarketCap").asDouble(0));
                q.put("volumeRatio",    1.0);
                q.put("market",         item.path("tsCode").asText("").endsWith(".SH") ? 1 : 0);
                all.add(q);
            }
            return all;
        } catch (Exception e) {
            log.debug("Python market_daily 接口不可用: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 分页获取全量A股实时快照（新浪财经行情列表接口）—— 降级方案
     * 每页80条，A股约5000只，约70次请求
     */
    private List<Map<String, Object>> fetchAllStockQuotesFromSina() {
        List<Map<String, Object>> all = new ArrayList<>();
        int maxPage = 70;
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int page = 1; page <= maxPage; page++) {
            try {
                String url = String.format(SINA_STOCK_LIST_URL, page);
                String body = httpGet(url);
                if (body == null || body.trim().isEmpty() || body.trim().equals("null")) break;

                com.fasterxml.jackson.databind.JsonNode arr;
                try {
                    arr = om.readTree(body);
                } catch (Exception e) {
                    log.debug("新浪行情第{}页解析失败: {}", page, e.getMessage());
                    break;
                }
                if (!arr.isArray() || arr.size() == 0) break;

                for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                    String symbol = item.path("symbol").asText("");
                    String code   = item.path("code").asText("");
                    if (code.isEmpty()) continue;

                    Map<String, Object> q = new HashMap<>();
                    q.put("code",           code);
                    q.put("name",           item.path("name").asText(code));
                    q.put("price",          parseDouble(item.path("trade").asText("0")));
                    q.put("changePercent",  item.path("changepercent").asDouble(0));
                    q.put("change",         item.path("pricechange").asDouble(0));
                    q.put("volume",         item.path("volume").asLong(0));
                    q.put("amount",         item.path("amount").asDouble(0));
                    q.put("turnoverRate",   item.path("turnoverratio").asDouble(0));
                    q.put("pe",             item.path("per").asDouble(0));
                    q.put("pb",             item.path("pb").asDouble(0));
                    q.put("totalMarketCap", item.path("mktcap").asDouble(0) / 10000.0);
                    q.put("circulationMarketCap", item.path("nmc").asDouble(0) / 10000.0);
                    q.put("high",           parseDouble(item.path("high").asText("0")));
                    q.put("low",            parseDouble(item.path("low").asText("0")));
                    q.put("open",           parseDouble(item.path("open").asText("0")));
                    q.put("prevClose",      parseDouble(item.path("settlement").asText("0")));
                    q.put("volumeRatio",    1.0);
                    q.put("market",         symbol.startsWith("sh") ? 1 : 0);
                    all.add(q);
                }

                if (arr.size() < 80) break;
                Thread.sleep(200);
            } catch (Exception e) {
                log.error("新浪分页获取行情失败 page={}: {}", page, e.getMessage());
                break;
            }
        }
        log.info("新浪财经获取全量A股行情：{}只", all.size());
        return all;
    }

    /** 安全解析字符串为double */
    private double parseDouble(String s) {
        if (s == null || s.isEmpty() || s.equals("--") || s.equals("null")) return 0;
        try { return Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /**
     * 预筛选条件（快速过滤，不依赖K线）
     * 过滤掉：ST、停牌、今日涨停追高、市值过小、无效数据
     */
    private List<Map<String, Object>> preFilter(List<Map<String, Object>> quotes) {
        return preFilter(quotes, 0);
    }

    /**
     * 预筛选条件（含资金过滤）
     *
     * @param quotes             全量行情快照
     * @param maxAffordablePrice 账户可承受的最高股价（= availableCash / 100），0 表示不过滤。
     *                           例：账户可用 10000 元，最多能买 1 手（100股），
     *                           maxAffordablePrice = 10000 / 100 = 100 元，
     *                           超过 100 元/股的标的直接过滤（买不起1手）。
     */
    private List<Map<String, Object>> preFilter(List<Map<String, Object>> quotes, double maxAffordablePrice) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> q : quotes) {
            String code = (String) q.get("code");
            String name = (String) q.get("name");
            double price = toDouble(q.get("price"));
            double changePercent = toDouble(q.get("changePercent"));
            double turnoverRate = toDouble(q.get("turnoverRate"));
            double totalMarketCap = toDouble(q.get("totalMarketCap"));
            double pe = toDouble(q.get("pe"));
            double volumeRatio = toDouble(q.get("volumeRatio"));

            // 过滤无效数据
            if (code == null || code.isEmpty() || price <= 0) continue;

            // [P1 停牌封控] 过滤当日停牌股票
            Set<String> suspended = getSuspendedStocks();
            if (!suspended.isEmpty() && suspended.contains(code)) {
                continue;  // 跳过停牌股
            }

            // 过滤ST、退市、科创板(688)涨跌幅限制不同
            if (name != null && (name.contains("ST") || name.contains("退"))) continue;

            // 过滤今日已涨停的（收盘追高风险大，A股10%涨停）
            if (changePercent >= 9.5) continue;

            // 过滤已跌停（-9.5%以下，说明有重大利空）
            if (changePercent <= -9.5) continue;

            // 过滤市值过小（小于10亿，流动性差）；totalMarketCap=0 表示数据源未提供，跳过该过滤
            if (totalMarketCap > 0 && totalMarketCap < 10) continue;

            // 过滤股价过低（1元以下，风险大）
            if (price < 1.0) continue;

            // [资金过滤] 过滤账户买不起的高价股（至少能买100股 = 1手）
            // maxAffordablePrice = availableCash / 100，0 表示不限制（大资金账户）
            if (maxAffordablePrice > 0 && price > maxAffordablePrice) continue;

            // 过滤量比异常（>10说明可能炒作，<0.1可能停牌）
            if (volumeRatio > 0 && (volumeRatio > 10 || volumeRatio < 0.1)) continue;

            result.add(q);
        }
        return result;
    }

    /**
     * 并行对候选股票获取K线+技术分析
     * 限制并发数，避免接口限流
     */
    private List<ScreenResult> analyzeWithKline(List<Map<String, Object>> candidates, int minScore) {
        List<ScreenResult> results = Collections.synchronizedList(new ArrayList<>());
        // 限制最多分析500只，避免耗时过长
        int limit = Math.min(candidates.size(), 500);
        List<Map<String, Object>> targetList = candidates.subList(0, limit);

        // 用固定线程池（4线程）并行分析
        ExecutorService pool = Executors.newFixedThreadPool(4);
        LocalDate endDate = LocalDate.now();
        // [P1-1 优化] K线范围从1年缩短为120交易日（约80天日历），提升全市场扫描速度。
        // MA60/ATR最多需要120根数据，120天已满足所有指标计算需求。
        LocalDate startDate = endDate.minusDays(180); // 多拶120天（含假日缓冲）

        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        int total = targetList.size();

        for (Map<String, Object> q : targetList) {
            pool.submit(() -> {
                String code = (String) q.get("code");
                String name = (String) q.get("name");
                try {
                    List<StockBar> bars = dataProvider.getDailyBars(
                            code, startDate, endDate, StockBar.AdjustType.FORWARD);

                    if (bars == null || bars.size() < 60) return;

                    AnalysisResult ar = analyzer.analyze(code, name, bars);
                    if (ar == null || ar.getOverallScore() < minScore) return;

                    double price = toDouble(q.get("price"));
                    double changePercent = toDouble(q.get("changePercent"));
                    double turnoverRate = toDouble(q.get("turnoverRate"));
                    double pe = toDouble(q.get("pe"));
                    double totalMarketCap = toDouble(q.get("totalMarketCap"));

                    // [P1-2 优化] 将 bars 一并存入 ScreenResult，供同轮 scanStock() 复用，避免重复拉K线
                    ScreenResult sr = new ScreenResult(
                            code, name, price, changePercent, turnoverRate, pe, totalMarketCap,
                            ar.getOverallScore(), ar.getOverallScore(),
                            ar.getTrend().getDescription(),
                            ar.getRecommendation().getDescription(),
                            ar, bars
                    );
                    results.add(sr);
                } catch (Exception e) {
                    log.debug("分析股票{}失败: {}", code, e.getMessage());
                } finally {
                    // 使用 AtomicInteger 保证多线程下计数准确（原 int[] 有并发问题）
                    int done = counter.incrementAndGet();
                    if (done % 50 == 0) {
                        log.info("  分析进度: {}/{}", done, total);
                    }
                }
            });
        }

        pool.shutdown();
        try {
            // 最多等待10分钟
            pool.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return results;
    }

    /**
     * 快速选股（仅用行情快照评分，不拉取K线，速度极快）
     * 适合日内快速筛选
     */
    public List<ScreenResult> quickScreen(int topN) {
        log.info("快速选股（仅基于行情快照）...");
        List<Map<String, Object>> quotes = fetchAllStockQuotes();
        List<Map<String, Object>> filtered = preFilter(quotes);

        // 用行情快照的量比、涨幅等快速评分
        List<ScreenResult> results = new ArrayList<>();
        for (Map<String, Object> q : filtered) {
            String code = (String) q.get("code");
            String name = (String) q.get("name");
            double price = toDouble(q.get("price"));
            double changePercent = toDouble(q.get("changePercent"));
            double turnoverRate = toDouble(q.get("turnoverRate"));
            double pe = toDouble(q.get("pe"));
            double totalMarketCap = toDouble(q.get("totalMarketCap"));
            double volumeRatio = toDouble(q.get("volumeRatio"));

            // 快速评分（0~100）
            int score = 50;
            // 今日小幅上涨（1~5%）
            if (changePercent >= 1 && changePercent <= 5) score += 10;
            else if (changePercent > 5 && changePercent < 9.5) score += 5;
            else if (changePercent < 0) score -= 10;
            // 量比放大
            if (volumeRatio >= 1.5 && volumeRatio <= 5) score += 10;
            else if (volumeRatio > 5) score += 5;
            // 换手率适中
            if (turnoverRate >= 2 && turnoverRate <= 8) score += 5;
            // PE适中（10~50）
            if (pe > 10 && pe < 50) score += 5;
            else if (pe < 0) score -= 5; // 亏损
            // 中盘股加分（100~1000亿）
            if (totalMarketCap >= 100 && totalMarketCap <= 1000) score += 5;

            if (score < 55) continue;

            results.add(new ScreenResult(code, name, price, changePercent, turnoverRate,
                    pe, totalMarketCap, score, score, "-", "-", null));
        }

        Collections.sort(results);
        return results.stream().limit(topN).collect(Collectors.toList());
    }

    /**
     * 技术面 + 基本面联合选股
     * <p>
     * 在标准技术面选股基础上，额外拉取基本面因子，排除财务危险股，
     * 对高质量低估值股票给予额外加分。
     * <p>
     * 注意：基本面因子需要 Tushare 2000+ 积分，积分不足时退化为纯技术面选股。
     *
     * @param topN               选出前N只
     * @param minScore           技术面最低评分
     * @param useFundamental     是否启用基本面过滤（需要高积分Tushare）
     * @param techWeight         技术面权重（0-1，如0.6表示技术面60%+基本面40%）
     */
    public List<ScreenResult> screenWithFundamental(int topN, int minScore,
                                                     boolean useFundamental, double techWeight) {
        return screenWithFundamental(topN, minScore, useFundamental, techWeight, 0.0, 0.0, 0);
    }

    /**
     * 技术面 + 基本面联合选股（含 ROE 和营收增速硬过滤）
     * <p>
     * 在 {@link #screenWithFundamental(int, int, boolean, double)} 基础上额外增加：
     *   - ROE 最低阈值过滤：ROE < roeMin 的股票直接排除
     *   - 营收同比增速过滤：revenueYoy < revenueYoyMin 的股票直接排除
     * 若 roeMin <= 0 且 revenueYoyMin <= 0，则不执行额外过滤（与不带参数版本等效）。
     * <p>
     * 特别说明：当基本面数据返回 0（积分不足时的降级值）时，不触发过滤（防止误杀）。
     *
     * @param topN               选出前N只
     * @param minScore           技术面最低评分
     * @param useFundamental     是否启用基本面过滤（需要高积分Tushare）
     * @param techWeight         技术面权重（0-1，如0.6表示技术面60%+基本面40%）
     * @param roeMin             ROE 最低阈值（%），0 表示不过滤
     * @param revenueYoyMin      营收同比增速最低阈值（%），0 表示不过滤
     */
    public List<ScreenResult> screenWithFundamental(int topN, int minScore,
                                                     boolean useFundamental, double techWeight,
                                                     double roeMin, double revenueYoyMin) {
        return screenWithFundamental(topN, minScore, useFundamental, techWeight, roeMin, revenueYoyMin, 0);
    }

    /**
     * 技术面 + 基本面联合选股（含 ROE、营收增速硬过滤 + 资金可买过滤）
     *
     * @param maxAffordablePrice 账户可承受的最高股价（= availableCash / 100），0 表示不过滤
     */
    public List<ScreenResult> screenWithFundamental(int topN, int minScore,
                                                     boolean useFundamental, double techWeight,
                                                     double roeMin, double revenueYoyMin,
                                                     double maxAffordablePrice) {
        if (!useFundamental) {
            return screenTopStocks(topN, minScore, maxAffordablePrice);
        }

        log.info("技术+基本面联合选股（topN={} 技术面权重={}% ROE>={}% 营收增速>={}%）...",
                topN,
                String.format("%.0f", techWeight * 100),
                String.format("%.0f", roeMin),
                String.format("%.0f", revenueYoyMin));

        // Step1: 先跑技术面选股，拿到候选（放宽minScore以保留更多候选；同时传入资金限制）
        List<ScreenResult> techResults = screenTopStocks(Math.max(topN * 3, 30),
                Math.max(minScore - 10, 50), maxAffordablePrice);
        if (techResults.isEmpty()) return new ArrayList<>();

        log.info("技术面候选：{}只，开始拉取基本面...", techResults.size());

        // Step2: 批量拉取基本面因子
        List<String> codes = techResults.stream().map(r -> r.stockCode).collect(Collectors.toList());
        Map<String, String> nameMap = new LinkedHashMap<>();
        techResults.forEach(r -> nameMap.put(r.stockCode, r.stockName));
        Map<String, FundamentalFactor> fundMap = fundamentalProvider.getBatchFundamental(codes, nameMap);

        // Step3: 综合评分（技术面 + 基本面）
        List<ScreenResult> combined = new ArrayList<>();
        for (ScreenResult r : techResults) {
            FundamentalFactor f = fundMap.get(r.stockCode);
            if (f == null || f.isFinanciallyRisky()) {
                log.debug("[{}] {} 基本面危险，跳过（负债率={}% 利润同比={}%）",
                        r.stockCode, r.stockName,
                        String.format("%.1f", f != null ? f.getDebtRatio() : 0.0),
                        String.format("%.1f", f != null ? f.getProfitYoy() : 0.0));
                continue;
            }
            // ROE 硬过滤：仅当数据非零（积分充足时）才执行过滤
            if (roeMin > 0 && f.getRoe() > 0 && f.getRoe() < roeMin) {
                log.debug("[{}] {} ROE={}% < 阈值{}%，基本面质量不达标，跳过",
                        r.stockCode, r.stockName,
                        String.format("%.1f", f.getRoe()),
                        String.format("%.0f", roeMin));
                continue;
            }
            // 营收增速硬过滤：仅当数据非零时执行过滤
            if (revenueYoyMin > 0 && f.getRevenueYoy() != 0 && f.getRevenueYoy() < revenueYoyMin) {
                log.debug("[{}] {} 营收同比={}% < 阈值{}%，营收增速不达标，跳过",
                        r.stockCode, r.stockName,
                        String.format("%.1f", f.getRevenueYoy()),
                        String.format("%.0f", revenueYoyMin));
                continue;
            }
            // 综合评分
            int combinedScore = f.combinedScore(r.techScore, techWeight);
            if (combinedScore < minScore) continue;

            // 重构ScreenResult（preScore用综合评分）
            combined.add(new ScreenResult(
                    r.stockCode, r.stockName, r.currentPrice,
                    r.changePercent, r.turnoverRate, r.pe,
                    r.totalMarketCap > 0 ? r.totalMarketCap : f.getTotalMv() / 10000.0,
                    r.techScore, combinedScore,
                    r.trend, r.recommendation, r.analysis
            ));
            log.debug("[{}] {} 综合评分={} (技术={} 基本面={} PE={} ROE={}%)",
                    r.stockCode, r.stockName, combinedScore, r.techScore,
                    f.getFundamentalScore(),
                    String.format("%.1f", f.getPeTtm()),
                    String.format("%.1f", f.getRoe()));
        }

        // Step4: 按 preScore(综合评分)降序排列，取Top N
        combined.sort((a, b) -> Integer.compare(b.preScore, a.preScore));
        List<ScreenResult> topList = combined.stream().limit(topN).collect(Collectors.toList());

        log.info("技术+基本面联合选股完成，Top{}：", topN);
        for (int i = 0; i < topList.size(); i++) {
            ScreenResult sr = topList.get(i);
            FundamentalFactor f = fundMap.get(sr.stockCode);
            log.info("  第{}名: {} {} 综合评分={} 技术={} PE={} ROE={}%",
                    i + 1, sr.stockCode, sr.stockName, sr.preScore, sr.techScore,
                    String.format("%.1f", f != null ? f.getPeTtm() : 0.0),
                    String.format("%.1f", f != null ? f.getRoe() : 0.0));
        }
        return topList;
    }

    private double toDouble(Object val) {
        if (val == null) return 0;
        try {
            return ((Number) val).doubleValue();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * [P1-1] 主力资金流向过滤：剔除近3日主力持续净流出的股票
     * <p>
     * 调用东方财富个股资金流向接口，获取近3日主力净流入数据。
     * 过滤条件：近3日主力净流入累计 < 0（持续流出），则降权或排除。
     * <p>
     * 接口：https://push2.eastmoney.com/api/qt/stock/fflow/kline/get
     * 参数：lmt=0&klt=101&secid=市场.代码&fields1=f1,f2&fields2=f51,f52,f53,f54,f55
     *
     * @param quotes 候选股票行情列表
     * @return 过滤后的候选股票列表
     */
    private List<Map<String, Object>> filterByMoneyFlow(List<Map<String, Object>> quotes) {
        if (quotes == null || quotes.isEmpty()) return quotes;

        List<Map<String, Object>> result = new ArrayList<>();
        int filteredCount = 0;
        int errorCount = 0;

        for (Map<String, Object> q : quotes) {
            String code = (String) q.get("code");
            if (code == null || code.isEmpty()) {
                result.add(q);
                continue;
            }

            try {
                // 构建东方财富资金流向接口地址
                // secid格式：1=沪市（600/601/603/688开头），0=深市（000/002/300开头）
                String market = (code.startsWith("6") || code.startsWith("9")) ? "1" : "0";
                String url = EMC_MONEY_FLOW_URL +
                        "?lmt=0&klt=101&secid=" + market + "." + code +
                        "&fields1=f1,f2&fields2=f51,f52,f53,f54,f55" +
                        "&ut=b2884a393a59ad64002292a3e90d46a5" +
                        "&cb=jQuery&_=" + System.currentTimeMillis();

                String json = httpGet(url);

                // 解析响应（JSONP格式，需去除回调包装）
                if (json == null || json.isEmpty()) {
                    result.add(q); // 请求失败不过滤
                    continue;
                }
                // 去掉JSONP包装 jQuery(...) -> {...}
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start < 0 || end < 0 || start >= end) {
                    result.add(q);
                    continue;
                }
                json = json.substring(start, end + 1);

                com.fasterxml.jackson.databind.ObjectMapper mapper =
                        new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
                com.fasterxml.jackson.databind.JsonNode klines = root.path("data").path("klines");

                if (!klines.isArray() || klines.size() == 0) {
                    result.add(q); // 无数据不过滤
                    continue;
                }

                // 取近3日数据（最新的3条），计算主力净流入累计
                // kline格式：日期,主力净流入,小单净流入,中单净流入,大单净流入,超大单净流入
                double totalMainFlow = 0;
                int days = Math.min(3, klines.size());
                for (int i = klines.size() - days; i < klines.size(); i++) {
                    String kline = klines.get(i).asText("");
                    String[] parts = kline.split(",");
                    if (parts.length >= 2) {
                        try {
                            totalMainFlow += Double.parseDouble(parts[1].trim()); // 主力净流入（元）
                        } catch (NumberFormatException ignore) {}
                    }
                }

                // 过滤条件：近3日主力累计净流出且超过-5000万
                final double FILTER_THRESHOLD = -5.0e7; // -5000万
                if (totalMainFlow < FILTER_THRESHOLD) {
                    filteredCount++;
                    log.debug("[P1-1] {} 近3日主力净流入={}万，持续流出，过滤",
                            code, String.format("%.0f", totalMainFlow / 10000));
                    continue;
                }

                result.add(q);

            } catch (Exception e) {
                errorCount++;
                result.add(q); // 发生异常时保留，不过滤
                log.debug("[P1-1] {} 资金流向查询异常: {}", code, e.getMessage());
            }
        }

        if (filteredCount > 0 || errorCount > 0) {
            log.info("[P1-1] 资金流向过滤：剔除主力持续流出{}只，查询异常{}只（保留），剩余{}只",
                    filteredCount, errorCount, result.size());
        }
        return result;
    }

    private String httpGet(String url) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
                        "AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header("Referer", "http://vip.stock.finance.sina.com.cn/")
                .build();
        try (okhttp3.Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
            return resp.body() != null ? resp.body().string() : "";
        }
    }
}

