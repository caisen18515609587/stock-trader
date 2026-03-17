package com.stocktrader;

import com.stocktrader.analysis.FactorIcTester;
import com.stocktrader.analysis.StockAnalyzer;
import com.stocktrader.analysis.StockScreener;
import com.stocktrader.config.DataMigration;
import com.stocktrader.config.DatabaseManager;
import com.stocktrader.config.SystemConfig;
import com.stocktrader.datasource.StockDataProvider;
import com.stocktrader.datasource.TongHuaShunDataProvider;
import com.stocktrader.model.*;
import com.stocktrader.report.TradeReporter;
import com.stocktrader.strategy.CompositeStrategy;
import com.stocktrader.strategy.MACDStrategy;
import com.stocktrader.strategy.RSIStrategy;
import com.stocktrader.strategy.TradingStrategy;
import com.stocktrader.trading.AutoTrader;
import com.stocktrader.trading.BacktestEngine;
import com.stocktrader.trading.SimulationEngine;
import com.stocktrader.util.PlatformServer;
import com.stocktrader.util.UserService;
import com.stocktrader.util.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 股票智能交易系统 - 主程序入口
 * <p>
 * 系统功能：
 * 1. 对接同花顺/东方财富等数据源，获取实时和历史行情
 * 2. 使用MA、MACD、RSI、KDJ、布林带等技术指标分析股票走势
 * 3. 通过策略引擎自动生成买卖信号
 * 4. 支持模拟交易（虚拟资金）和历史回测
 * 5. 统计收益率，生成详细报告，总结交易经验
 * 6. 未来可升级为实盘交易
 */
public class StockTraderApplication {

    private static final Logger log = LoggerFactory.getLogger(StockTraderApplication.class);

    public static void main(String[] args) {
        printBanner();

        SystemConfig config = SystemConfig.getInstance();
        log.info("系统启动，运行模式：{}", config.getSystemMode().getDescription());

        // 初始化数据源
        String thsToken = config.getThsToken();
        StockDataProvider dataProvider = new TongHuaShunDataProvider(thsToken);
        log.info("数据源：{}", dataProvider.getProviderName());

        // 检查命令行参数
        if (args.length > 0) {
            switch (args[0].toLowerCase()) {
                case "backtest":
                    runBacktest(dataProvider, config, args);
                    return;
                case "simulation":
                    runSimulation(dataProvider, config);
                    return;
                case "analyze":
                    runAnalysis(dataProvider, args);
                    return;
                case "demo":
                    runDemo(dataProvider);
                    return;
                case "auto":
                    runAutoTrader(dataProvider, config, args);
                    return;
                case "screen":
                    runScreen(dataProvider, args);
                    return;
                case "ictest":
                    runIcTest(dataProvider, args);
                    return;
                case "platform":
                    runPlatform(dataProvider, config, args);
                    return;
                case "init-admin":
                    // 用法: init-admin <用户名> <密码>
                    runInitAdmin(config, args);
                    return;
            }
        }

        // 交互式菜单
        runInteractiveMenu(dataProvider, config);
    }

    /**
     * 交互式主菜单
     */
    private static void runInteractiveMenu(StockDataProvider dataProvider, SystemConfig config) {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n==================================================");
            System.out.println("        股票智能交易系统 v1.0");
            System.out.println("==================================================");
        System.out.println("  1. 历史回测（验证策略有效性）");
        System.out.println("  2. 模拟交易（实时行情，虚拟资金）");
        System.out.println("  3. 分析单只股票");
        System.out.println("  4. 运行演示（Demo）");
        System.out.println("  5. 查看系统配置");
        System.out.println("  6. 🤖 全自动选股+模拟交易（推荐）");
        System.out.println("  7. 📊 全市场选股扫描");
        System.out.println("  8. 🌐 启动多用户交易平台");
        System.out.println("  9. 🔬 技术因子 IC 历史检验（验证指标有效性）");
        System.out.println("  0. 退出");
            System.out.println("-------------------------------------------------");
            System.out.print("请选择：");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    runBacktestInteractive(scanner, dataProvider);
                    break;
                case "2":
                    runSimulation(dataProvider, config);
                    break;
                case "3":
                    runAnalysisInteractive(scanner, dataProvider);
                    break;
                case "4":
                    runDemo(dataProvider);
                    break;
                case "5":
                    printConfig(config);
                    break;
                case "6":
                    runAutoTrader(dataProvider, config, new String[]{"auto"});
                    break;
                case "7":
                    runScreen(dataProvider, new String[]{"screen"});
                    break;
                case "8":
                    runPlatform(dataProvider, config, new String[]{"platform"});
                    break;
                case "9":
                    runIcTest(dataProvider, new String[]{"ictest"});
                    break;
                case "0":
                    System.out.println("系统退出，感谢使用！");
                    return;
                default:
                    System.out.println("无效选项，请重新选择");
            }
        }
    }

    /**
     * 历史回测（交互式）
     */
    private static void runBacktestInteractive(Scanner scanner, StockDataProvider dataProvider) {
        System.out.print("\n请输入股票代码（如600519）：");
        String code = scanner.nextLine().trim();
        if (code.isEmpty()) code = "600519";

        System.out.print("回测开始日期（如20230101，默认一年前）：");
        String startInput = scanner.nextLine().trim();
        LocalDate startDate = startInput.isEmpty() ?
                LocalDate.now().minusYears(1) :
                LocalDate.parse(startInput, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        System.out.print("回测结束日期（如20240101，默认今天）：");
        String endInput = scanner.nextLine().trim();
        LocalDate endDate = endInput.isEmpty() ? LocalDate.now() :
                LocalDate.parse(endInput, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);

        System.out.print("初始资金（元，默认100000）：");
        String capitalInput = scanner.nextLine().trim();
        double capital = capitalInput.isEmpty() ? 100000 : Double.parseDouble(capitalInput);

        runBacktestForStock(dataProvider, code, startDate, endDate, capital);
    }

    /**
     * 执行指定股票回测
     */
    private static void runBacktestForStock(StockDataProvider dataProvider,
                                             String stockCode, LocalDate startDate,
                                             LocalDate endDate, double capital) {
        log.info("开始获取{}的历史数据 {} ~ {}", stockCode, startDate, endDate);

        // 获取历史数据（多取一段用于预热）
        LocalDate dataStartDate = startDate.minusDays(120);
        List<StockBar> allBars = dataProvider.getDailyBars(stockCode, dataStartDate, endDate,
                StockBar.AdjustType.FORWARD);

        if (allBars == null || allBars.size() < 80) {
            System.out.printf("股票%s数据不足（获取%d根），无法回测%n",
                    stockCode, allBars == null ? 0 : allBars.size());
            return;
        }

        Stock stock = dataProvider.getStockInfo(stockCode);
        String stockName = stock != null ? stock.getName() : stockCode;

        System.out.printf("%n股票：%s %s，数据：%d根K线%n", stockCode, stockName, allBars.size());

        // 定义回测配置
        BacktestEngine.BacktestConfig config = new BacktestEngine.BacktestConfig(
                stockCode, stockName, capital, startDate, endDate);

        BacktestEngine backtestEngine = new BacktestEngine();
        TradeReporter reporter = new TradeReporter();

        // 对多个策略进行回测对比
        List<TradingStrategy> strategies = new ArrayList<>();
        strategies.add(new CompositeStrategy());
        strategies.add(new MACDStrategy());
        strategies.add(new RSIStrategy());

        List<BacktestEngine.BacktestResult> results = new ArrayList<>();
        for (TradingStrategy strategy : strategies) {
            BacktestEngine.BacktestResult result = backtestEngine.run(config, allBars, strategy);
            if (result != null) {
                results.add(result);
                reporter.printBacktestReport(result);
            }
        }

        // 策略对比
        if (results.size() > 1) {
            reporter.printStrategyComparison(results);
        }

        // 导出最佳策略的交易记录
        if (!results.isEmpty()) {
            BacktestEngine.BacktestResult best = results.stream()
                    .max(java.util.Comparator.comparingDouble(r -> r.totalReturn))
                    .orElse(results.get(0));
            try {
                String csvPath = String.format("./reports/backtest_%s_%s.csv", stockCode,
                        LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
                new java.io.File("./reports").mkdirs();
                reporter.exportTradesToCsv(best.tradeHistory, csvPath);
                System.out.printf("交易记录已导出：%s%n", csvPath);
            } catch (Exception e) {
                log.error("导出CSV失败", e);
            }
        }
    }

    /**
     * 分析单只股票（交互式）
     */
    private static void runAnalysisInteractive(Scanner scanner, StockDataProvider dataProvider) {
        System.out.print("\n请输入股票代码（如600519）：");
        String code = scanner.nextLine().trim();
        if (code.isEmpty()) code = "600519";
        runAnalysis(dataProvider, new String[]{"analyze", code});
    }

    /**
     * 分析单只股票（命令行模式）
     * <p>
     * 数据获取顺序：
     * 1. 获取近一年历史日K线（用于技术指标计算）
     * 2. 调用 getRealTimeQuote 获取盘中实时价格（新浪财经实时接口）
     * 3. 将实时行情覆盖/追加到K线末尾，确保"当前价"为盘中真实价格
     */
    private static void runAnalysis(StockDataProvider dataProvider, String[] args) {
        String stockCode = args.length > 1 ? args[1] : "600519";
        log.info("开始分析股票：{}", stockCode);

        // 1. 获取近一年历史K线（用于技术指标计算）
        List<StockBar> bars = dataProvider.getDailyBars(stockCode,
                LocalDate.now().minusYears(1), LocalDate.now(), StockBar.AdjustType.FORWARD);

        if (bars == null || bars.size() < 60) {
            System.out.printf("股票%s数据不足，无法分析%n", stockCode);
            return;
        }

        // 2. 获取盘中实时行情（新浪财经，支持盘中价格）
        String priceSource = "日线收盘价";
        try {
            StockBar realtime = dataProvider.getRealTimeQuote(stockCode);
            if (realtime != null && realtime.getClose() > 0) {
                // 判断日期是否是今天
                java.time.LocalDate rtDate = realtime.getDate();
                java.time.LocalDate today = java.time.LocalDate.now();
                if (rtDate != null && (rtDate.equals(today) || rtDate.equals(today.minusDays(1)))) {
                    // 如果末尾K线是同一天，则替换（用实时价覆盖日线收盘价）
                    if (!bars.isEmpty() && bars.get(bars.size() - 1).getDate() != null
                            && bars.get(bars.size() - 1).getDate().equals(rtDate)) {
                        bars.set(bars.size() - 1, realtime);
                    } else {
                        bars.add(realtime);
                    }
                    priceSource = rtDate.equals(today) ? "盘中实时价" : "昨日收盘价(已闭市)";
                    log.info("实时行情追加成功: {} 价格={} 来源=新浪财经", stockCode, realtime.getClose());
                }
            }
        } catch (Exception e) {
            log.warn("获取实时行情失败，使用历史K线末尾价: {}", e.getMessage());
        }

        Stock stock = dataProvider.getStockInfo(stockCode);
        String stockName = stock != null ? stock.getName() : stockCode;

        StockAnalyzer analyzer = new StockAnalyzer();
        AnalysisResult result = analyzer.analyze(stockCode, stockName, bars);

        System.out.println("\n" + result.getSummary());
        System.out.printf("  📡 价格来源: %s（新浪财经实时接口）%n", priceSource);
    }

    /**
     * 实时模拟交易
     */
    private static void runSimulation(StockDataProvider dataProvider, SystemConfig config) {
        double capital = config.getInitialCapital();
        List<String> watchlist = config.getWatchlistStocks();

        log.info("启动模拟交易，初始资金：{}，监控{}只股票", capital, watchlist.size());

        Portfolio portfolio = new Portfolio(
                "sim_" + System.currentTimeMillis(),
                "模拟账户",
                capital,
                Portfolio.AccountMode.SIMULATION
        );

        SimulationEngine engine = new SimulationEngine(dataProvider);
        engine.addStrategy(new CompositeStrategy());
        engine.addStrategy(new MACDStrategy());

        // 执行一次扫描
        List<TradeSignal> signals = engine.runSimulation(portfolio, watchlist);

        // 打印账户状态
        engine.printPortfolioSummary(portfolio);

        // 报告
        new TradeReporter().printAccountReport(portfolio);

        System.out.printf("%n本次扫描产生 %d 个交易信号%n", signals.size());
        for (TradeSignal signal : signals) {
            if (signal.getSignalType() != TradeSignal.SignalType.HOLD) {
                System.out.printf("  [%s] %s %s 强度:%d - %s%n",
                        signal.getStrategyName(), signal.getStockCode(),
                        signal.getSignalType().getDescription(),
                        signal.getStrength(), signal.getReason());
            }
        }
    }

    /**
     * 全自动选股+模拟交易模式
     * 用法: auto [资金] [topN] [最低评分] [扫描间隔分钟]
     * 示例: auto 100000 3 60 30
     */
    private static void runAutoTrader(StockDataProvider dataProvider, SystemConfig config, String[] args) {
        double capital = args.length > 1 ? parseDoubleOrDefault(args[1], config.getInitialCapital())
                : config.getInitialCapital();
        int topN = args.length > 2 ? parseIntOrDefault(args[2], 3) : 3;
        int minScore = args.length > 3 ? parseIntOrDefault(args[3], 60) : 60;
        int interval = args.length > 4 ? parseIntOrDefault(args[4], 30) : 30;

        log.info("启动全自动交易：资金={} TopN={} 最低评分={} 扫描间隔={}分钟",
                capital, topN, minScore, interval);

        // 打印微信推送提示
        boolean pushEnabled = config.getBoolean("push.wechat.enabled", false);
        String sendKey = config.get("push.wechat.sendkey", "");
        if (pushEnabled && !sendKey.isEmpty()) {
            System.out.println("\n  ✅ 微信推送已启用（Server酱），交易信号将推送到您的微信");
        } else {
            System.out.println("\n  ⚠️  微信推送未启用，如需接收交易通知请配置：");
            System.out.println("     1. 访问 https://sct.ftqq.com 获取 SendKey");
            System.out.println("     2. 在 application.properties 中设置：");
            System.out.println("        push.wechat.enabled=true");
            System.out.println("        push.wechat.sendkey=你的SendKey");
        }

        // 打印回调服务器提示（自动获取本机IP）
        if (config.isCallbackServerEnabled()) {
            int port = config.getCallbackServerPort();
            String localIp = getLocalIp();
            System.out.printf("  📱 交易反馈服务器地址：http://%s:%d%n", localIp, port);
            System.out.printf("     账户状态：http://%s:%d/status%n", localIp, port);
            System.out.printf("     操作帮助：http://%s:%d%n%n", localIp, port);
        }

        AutoTrader trader = new AutoTrader(dataProvider, capital, topN, minScore, interval);
        trader.start(); // 阻塞运行
    }

    /**
     * 全市场选股扫描（单次，不阻塞）
     * 用法: screen [topN] [最低评分]
     */
    private static void runScreen(StockDataProvider dataProvider, String[] args) {
        int topN = args.length > 1 ? parseIntOrDefault(args[1], 5) : 5;
        int minScore = args.length > 2 ? parseIntOrDefault(args[2], 60) : 60;

        log.info("开始全市场选股扫描，Top{} 最低评分{}", topN, minScore);
        StockScreener screener = new StockScreener(dataProvider);
        List<StockScreener.ScreenResult> results = screener.screenTopStocks(topN, minScore);

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════╗");
        System.out.printf("║  📊 全市场选股结果 Top%-3d（最低评分≥%d）%-31s║%n", topN, minScore, " ");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║  %-6s %-8s %7s %7s %7s %8s %6s  %-12s║%n",
                "代码", "名称", "现价", "涨跌幅", "换手率", "市值(亿)", "评分", "建议");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════╣");
        for (int i = 0; i < results.size(); i++) {
            StockScreener.ScreenResult r = results.get(i);
            System.out.printf("║  %2d. %-6s %-8s %7.2f %+6.2f%% %6.2f%% %8.1f %6d  %-12s║%n",
                    i + 1, r.stockCode, r.stockName, r.currentPrice, r.changePercent,
                    r.turnoverRate, r.totalMarketCap, r.techScore, r.recommendation);
        }
        System.out.println("╚══════════════════════════════════════════════════════════════════════════╝");

        if (results.isEmpty()) {
            System.out.println("  当前市场无符合条件的股票（评分不足），请降低评分门槛或在行情活跃时再试");
        }
    }

    /**
     * 技术因子 IC 历史检验
     * <p>
     * 用法: ictest [持有期交易日] [回测年数] [股票代码1,代码2,...]
     * 示例: ictest 5 2 600519,000001,300750,000547
     * <p>
     * 原理：
     * <ol>
     *   <li>每月第一个交易日为截面日期，计算所有指定股票在截面日的技术指标值</li>
     *   <li>计算该截面日起 N 个交易日后的真实收益率（未来收益）</li>
     *   <li>用 Spearman 秩相关系数衡量因子值与未来收益的线性相关性（IC）</li>
     *   <li>IC 均值 > 0.02 且 ICIR > 0.3 视为有效因子</li>
     * </ol>
     * <p>
     * 注意：至少需要 3 只以上股票才能计算有意义的截面 IC。
     * 股票数量越多，IC 结果越可靠。建议使用 10 只以上、跨行业的股票。
     */
    private static void runIcTest(StockDataProvider dataProvider, String[] args) {
        // 解析参数
        int holdingDays = args.length > 1 ? parseIntOrDefault(args[1], 5) : 5;
        int years        = args.length > 2 ? parseIntOrDefault(args[2], 2) : 2;

        // 股票代码列表：优先从参数读取，否则使用内置的跨行业代表股
        List<String> stockCodes;
        if (args.length > 3 && !args[3].isEmpty()) {
            stockCodes = java.util.Arrays.asList(args[3].split(","));
        } else {
            // 默认：沪深各行业代表股（共20只，保证截面股票数量充足）
            stockCodes = java.util.Arrays.asList(
                "600519",  // 贵州茅台（消费）
                "000858",  // 五粮液（消费）
                "601318",  // 中国平安（金融）
                "600036",  // 招商银行（银行）
                "000001",  // 平安银行（银行）
                "600276",  // 恒瑞医药（医药）
                "300750",  // 宁德时代（新能源）
                "002594",  // 比亚迪（新能源）
                "600900",  // 长江电力（公用事业）
                "601888",  // 中国中免（免税）
                "000547",  // 航天工业（国防军工）
                "688599",  // 天合光能（光伏）
                "601899",  // 紫金矿业（有色金属）
                "000063",  // 中兴通讯（通信）
                "600009",  // 上海机场（交通运输）
                "002415",  // 海康威视（安防电子）
                "000725",  // 京东方A（面板）
                "600031",  // 三一重工（工程机械）
                "000002",  // 万科A（房地产）
                "601012"   // 隆基绿能（光伏）
            );
        }

        LocalDate endDate   = LocalDate.now();
        LocalDate startDate = endDate.minusYears(years);

        System.out.println("\n================================================================");
        System.out.printf("【技术因子 IC 历史检验】持有期=%d日  检验区间：%s ~ %s%n",
                holdingDays, startDate, endDate);
        System.out.printf("股票池：%d 只  %s%n", stockCodes.size(), stockCodes);
        System.out.println("正在获取历史 K 线数据，请稍候...");
        System.out.println("================================================================");

        // 获取所有股票的历史 K 线
        java.util.Map<String, List<StockBar>> stockBarsMap = new java.util.LinkedHashMap<>();
        int loaded = 0;
        for (String code : stockCodes) {
            try {
                // 多取60天用于指标预热（MA60需要60根K线）
                List<StockBar> bars = dataProvider.getDailyBars(
                        code, startDate.minusDays(90), endDate, StockBar.AdjustType.FORWARD);
                if (bars != null && bars.size() >= 60) {
                    stockBarsMap.put(code, bars);
                    loaded++;
                    System.out.printf("  [%d/%d] %s 获取 %d 根K线 ✓%n",
                            loaded, stockCodes.size(), code, bars.size());
                } else {
                    System.out.printf("  [跳过] %s 数据不足（%d根）%n",
                            code, bars == null ? 0 : bars.size());
                }
            } catch (Exception e) {
                System.out.printf("  [失败] %s: %s%n", code, e.getMessage());
            }
        }

        if (stockBarsMap.size() < 3) {
            System.out.println("\n⚠️  有效股票数量不足（需要至少3只），无法进行截面IC检验。");
            System.out.println("  建议：检查数据服务是否正常（python app.py），或减少股票代码数量重试。");
            return;
        }

        System.out.printf("%n共加载 %d 只股票数据，开始 IC 检验...%n", stockBarsMap.size());

        // 执行技术因子批量 IC 检验
        FactorIcTester tester = new FactorIcTester();
        java.util.Map<String, FactorIcTester.IcResult> results =
                tester.batchTechTest(stockBarsMap, holdingDays, startDate, endDate);

        // 生成权重建议
        java.util.Map<String, Double> weightSuggestion =
                tester.generateWeightSuggestion(results, null);

        // 输出当前 StockAnalyzer 权重 vs IC建议权重对比
        System.out.println("\n================================================================");
        System.out.println("【当前 StockAnalyzer 权重 vs IC检验建议权重】");
        System.out.println("----------------------------------------------------------------");
        System.out.printf("  %-16s  %10s  %10s%n", "因子", "当前权重", "IC建议权重");
        System.out.println("----------------------------------------------------------------");
        // 当前权重（硬编码对照）
        java.util.Map<String, Double> currentWeights = new java.util.LinkedHashMap<>();
        currentWeights.put("MA_SCORE(ma)",        0.28);
        currentWeights.put("MOMENTUM_20",         0.20);
        currentWeights.put("MACD_DIF(macd)",      0.20);
        currentWeights.put("MOMENTUM_5",          0.12);
        currentWeights.put("RSI6(rsi)",           0.10);
        currentWeights.put("KDJ_J(kdj)",          0.06);
        currentWeights.put("BOLL_POS(bollinger)", 0.04);
        currentWeights.put("VOL_RATIO(volume)",   0.00);
        currentWeights.put("BOLL_WIDTH",          0.00);
        for (java.util.Map.Entry<String, Double> e : currentWeights.entrySet()) {
            // 尝试匹配 IC 建议权重中的因子名
            String key = e.getKey();
            double suggested = weightSuggestion.entrySet().stream()
                    .filter(we -> key.contains(we.getKey()) || we.getKey().contains(key.split("\\(")[0]))
                    .mapToDouble(java.util.Map.Entry::getValue)
                    .findFirst().orElse(-1);
            String suggestedStr = suggested < 0 ? "N/A(无历史数据)" : String.format("%.3f(%.1f%%)", suggested, suggested * 100);
            System.out.printf("  %-16s  %8.3f  %s%n", key, e.getValue(), suggestedStr);
        }
        System.out.println("================================================================");
        System.out.println("💡 建议：根据上述 IC 检验结果，");
        System.out.println("   在 StockAnalyzer.java 中调整 WEIGHT_* 常量以提升选股精准度。");
        System.out.println("   IC检验数据越多（股票越多/时间越长），建议越可靠。");
    }

    private static double parseDoubleOrDefault(String s, double def) {
        try { return Double.parseDouble(s); } catch (Exception e) { return def; }
    }

    private static int parseIntOrDefault(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    /**
     * 自动获取本机局域网 IP（非回环地址）
     */
    private static String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                java.util.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取本机IP失败: {}", e.getMessage());
        }
        return "127.0.0.1";
    }

    /**
     * 命令行回测模式
     */
    private static void runBacktest(StockDataProvider dataProvider, SystemConfig config, String[] args) {
        String stockCode = args.length > 1 ? args[1] : "600519";
        double capital = config.getInitialCapital();
        LocalDate startDate = LocalDate.now().minusYears(2);
        LocalDate endDate = LocalDate.now();
        runBacktestForStock(dataProvider, stockCode, startDate, endDate, capital);
    }

    /**
     * 演示模式（离线数据，不需要网络）
     */
    private static void runDemo(StockDataProvider dataProvider) {
        System.out.println("\n【演示模式】生成模拟K线数据进行回测演示...");

        // 生成模拟K线数据（使用低价股模拟，便于回测演示）
        List<StockBar> mockBars = generateMockBars("000858", 365);

        Portfolio portfolio = new Portfolio("demo_001", "演示账户", 100000,
                Portfolio.AccountMode.PAPER);

        BacktestEngine.BacktestConfig config = new BacktestEngine.BacktestConfig(
                "000858", "五粮液(模拟)", 100000,
                LocalDate.now().minusYears(1), LocalDate.now());

        BacktestEngine engine = new BacktestEngine();
        TradeReporter reporter = new TradeReporter();

        // 测试综合策略
        BacktestEngine.BacktestResult result = engine.run(config, mockBars, new CompositeStrategy());
        if (result != null) {
            reporter.printBacktestReport(result);
        }
    }

    /**
     * 生成模拟K线数据（用于演示，无需网络）
     */
    private static List<StockBar> generateMockBars(String stockCode, int days) {
        List<StockBar> bars = new ArrayList<>();
        double price = 100.0;  // 使用合理的低价格，便于资金运转
        java.util.Random random = new java.util.Random(42);
        LocalDate date = LocalDate.now().minusDays(days + 60);

        for (int i = 0; i < days + 60; i++) {
            // 跳过周末
            if (date.getDayOfWeek().getValue() >= 6) {
                date = date.plusDays(1);
                i--;
                continue;
            }

            // 模拟价格随机游走（带轻微上涨趋势）
            double change = (random.nextGaussian() * 0.02 + 0.0003) * price;
            double open = price;
            price = Math.max(price + change, 10);
            double high = Math.max(open, price) * (1 + random.nextDouble() * 0.01);
            double low = Math.min(open, price) * (1 - random.nextDouble() * 0.01);
            long volume = (long) (1000000 + random.nextGaussian() * 500000);
            volume = Math.max(volume, 100000);

            StockBar bar = StockBar.builder()
                    .stockCode(stockCode)
                    .dateTime(date.atStartOfDay())
                    .open(open)
                    .close(price)
                    .high(high)
                    .low(low)
                    .volume(volume)
                    .amount(price * volume)
                    .changePercent(change / (open > 0 ? open : 1) * 100)
                    .change(change)
                    .period(StockBar.BarPeriod.DAILY)
                    .adjustType(StockBar.AdjustType.FORWARD)
                    .build();
            bars.add(bar);
            date = date.plusDays(1);
        }
        return bars;
    }

    private static void printConfig(SystemConfig config) {
        System.out.println("\n【当前系统配置】");
        System.out.printf("  运行模式: %s%n", config.getSystemMode().getDescription());
        System.out.printf("  初始资金: %,.0f 元%n", config.getInitialCapital());
        System.out.printf("  监控股票: %s%n", config.getWatchlistStocks());
        System.out.printf("  最大仓位: %.0f%%%n", config.getMaxSingleStockRatio() * 100);
        System.out.printf("  最大持股: %d 只%n", config.getMaxPositions());
        System.out.printf("  同花顺Token: %s%n",
                config.getThsToken() != null && !config.getThsToken().isEmpty() ? "已配置" : "未配置（使用免费接口）");
    }

    /**
     * 初始化超级管理员账户（仅供内部使用）
     * 用法: init-admin <用户名> <密码>
     */
    private static void runInitAdmin(SystemConfig config, String[] args) {
        if (args.length < 3) {
            System.out.println("用法: init-admin <用户名> <密码>");
            System.out.println("示例: init-admin admin mypassword123");
            return;
        }
        String username = args[1];
        String password = args[2];
        String dataDir = config.get("data.dir", "./data");

        UserStore userStore = new UserStore(dataDir);
        UserService userService = new UserService(userStore);

        String result = userService.initSuperAdmin(username, password);
        System.out.println("\n  ✅ " + result);
        System.out.println("  用户名: " + username);
        StringBuilder stars = new StringBuilder();
        for (int i = 0; i < password.length(); i++) stars.append('*');
        System.out.println("  密  码: " + stars);
        System.out.println("  权  限: 超级管理员（不可被其他人修改/禁用）");
        System.out.println();
    }

    /**
     * 启动多用户交易平台
     * 用法: platform [端口号]
     * 示例: platform 8080
     */
    private static void runPlatform(StockDataProvider dataProvider, SystemConfig config, String[] args) {
        // 命令行参数优先，其次读配置文件，最后默认8080
        int port = args.length > 1
                ? parseIntOrDefault(args[1], config.getInt("platform.port", 8080))
                : config.getInt("platform.port", 8080);

        String dataDir = config.get("data.dir", "./data");

        // 首次启动：若数据库不存在但有旧 JSON 文件，自动执行迁移
        java.io.File dbFile = new java.io.File(dataDir + "/stock_trader.db");
        java.io.File usersJson = new java.io.File(dataDir + "/users.json");
        if (!dbFile.exists() && usersJson.exists()) {
            log.info("检测到旧版 JSON 数据文件，正在自动迁移到 SQLite 数据库...");
            new DataMigration().migrate(dataDir);
        } else {
            // 确保数据库已初始化（建表）
            DatabaseManager.getInstance(dataDir);
        }

        UserStore userStore = new UserStore(dataDir);
        UserService userService = new UserService(userStore);

        PlatformServer server = new PlatformServer(port, userService, dataProvider);
        server.start();

        String localIp = getLocalIp();
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║         🌐 多用户交易平台 已启动                    ║");
        System.out.println("  ╠══════════════════════════════════════════════════╣");
        System.out.printf("  ║  本机访问：http://localhost:%-4d                   ║%n", port);
        System.out.printf("  ║  局域网：  http://%-15s:%-4d            ║%n", localIp, port);
        System.out.println("  ╠══════════════════════════════════════════════════╣");
        System.out.println("  ║  功能：注册 / 登录 / 策略配置 / 自动交易              ║");
        System.out.println("  ║  每个用户数据完全隔离，独立持仓与报告                  ║");
        System.out.println("  ╚══════════════════════════════════════════════════╝");
        System.out.println("  平台运行中，发送 kill 信号或 Ctrl+C 可停止...");
        System.out.println();

        // 注册 ShutdownHook，收到停止信号时优雅关闭
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            log.info("平台已停止");
        }));

        // 永久阻塞主线程，避免 stdin 重定向到 /dev/null 时立即退出
        try { Thread.currentThread().join(); } catch (InterruptedException ignored) {}
    }

    private static void printBanner() {
        System.out.println();
        System.out.println("  ╔═══════════════════════════════════════════════╗");
        System.out.println("  ║        股票智能分析与量化交易系统 v1.0           ║");
        System.out.println("  ║   Stock Intelligent Trading System v1.0       ║");
        System.out.println("  ╠═══════════════════════════════════════════════╣");
        System.out.println("  ║  技术栈：Java 17 + 同花顺/东方财富数据源          ║");
        System.out.println("  ║  策略：MACD + RSI + KDJ + 布林带综合策略         ║");
        System.out.println("  ║  支持：历史回测 / 模拟交易 / 实盘接入              ║");
        System.out.println("  ╚═══════════════════════════════════════════════╝");
        System.out.println();
    }
}

