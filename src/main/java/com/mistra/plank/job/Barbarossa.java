package com.mistra.plank.job;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.thread.NamedThreadFactory;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.google.common.collect.Lists;
import com.mistra.plank.common.config.PlankConfig;
import com.mistra.plank.common.util.HttpUtil;
import com.mistra.plank.dao.DailyRecordMapper;
import com.mistra.plank.dao.HoldSharesMapper;
import com.mistra.plank.dao.StockMapper;
import com.mistra.plank.model.dto.StockMainFundSample;
import com.mistra.plank.model.dto.StockRealTimePrice;
import com.mistra.plank.model.entity.Bk;
import com.mistra.plank.model.entity.DailyRecord;
import com.mistra.plank.model.entity.HoldShares;
import com.mistra.plank.model.entity.Stock;
import com.mistra.plank.model.enums.AutomaticTradingEnum;
import com.mistra.plank.service.impl.ScreeningStocks;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.mistra.plank.common.config.SystemConstant.W;
import static com.mistra.plank.common.util.StringUtil.collectionToString;

/**
 * 涨停先锋
 *
 * @author mistra@future.com
 * @date 2021/11/19
 */
@Slf4j
@Component
public class Barbarossa implements CommandLineRunner {

    private static final int availableProcessors = Runtime.getRuntime().availableProcessors();
    private final StockMapper stockMapper;
    private final StockProcessor stockProcessor;
    private final DailyRecordMapper dailyRecordMapper;
    private final HoldSharesMapper holdSharesMapper;
    private final PlankConfig plankConfig;
    private final ScreeningStocks screeningStocks;
    private final DailyRecordProcessor dailyRecordProcessor;
    private final AnalyzeProcessor analyzePlank;
    @Autowired(required = false)
    private AutomaticPlankTrading automaticPlankTrading;
    public static final ThreadPoolExecutor executorService = new ThreadPoolExecutor(availableProcessors * 2,
            availableProcessors * 2, 100L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(5000), new NamedThreadFactory("Plank-", false));
    /**
     * 所有股票 key-code value-name
     */
    public static final HashMap<String, String> STOCK_ALL_MAP = new HashMap<>(4096);
    /**
     * 需要监控关注的机构趋势票 key-name value-Stock
     */
    public static final HashMap<String, Stock> STOCK_TRACK_MAP = new HashMap<>(32);
    /**
     * 主力流入数据
     */
    public static final CopyOnWriteArrayList<StockMainFundSample> MAIN_FUND_DATA = new CopyOnWriteArrayList<>();
    public static final ConcurrentHashMap<String, StockMainFundSample> MAIN_FUND_DATA_MAP =
            new ConcurrentHashMap<>(4096);
    /**
     * 是否开启监控中
     */
    private final AtomicBoolean monitoring = new AtomicBoolean(false);

    public Barbarossa(StockMapper stockMapper, StockProcessor stockProcessor, DailyRecordMapper dailyRecordMapper,
                      HoldSharesMapper holdSharesMapper, PlankConfig plankConfig, ScreeningStocks screeningStocks,
                      DailyRecordProcessor dailyRecordProcessor, AnalyzeProcessor analyzePlank) {
        this.stockMapper = stockMapper;
        this.stockProcessor = stockProcessor;
        this.dailyRecordMapper = dailyRecordMapper;
        this.holdSharesMapper = holdSharesMapper;
        this.plankConfig = plankConfig;
        this.screeningStocks = screeningStocks;
        this.dailyRecordProcessor = dailyRecordProcessor;
        this.analyzePlank = analyzePlank;
    }

    @Override
    public void run(String... args) {
        opening();
    }

    @Scheduled(cron = "0 */2 * * * ?")
    private void executorStatus() {
        if (AutomaticTrading.isTradeTime() && plankConfig.getAutomaticPlankTrading()) {
//            log.error("ThreadPoolExecutor core:{},max:{},queue:{}", Barbarossa.executorService.getCorePoolSize(),
//                    Barbarossa.executorService.getMaximumPoolSize(), Barbarossa.executorService.getQueue().size());
            log.error("打板一级缓存:{}", collectionToString(AutomaticPlankTrading.STOCK_AUTO_PLANK_FILTER_MAP.values()
                    .stream().map(Stock::getName).collect(Collectors.toList())));
        }
    }

    /**
     * 初始化股票基本数据
     */
    private void updateStockCache() {
        List<Stock> stocks = stockMapper.selectList(new QueryWrapper<Stock>()
                // 默认过滤掉了北交所,科创板,ST
                .notLike("name", "%ST%").notLike("code", "%688%")
                .notLike("name", "%st%").notLike("name", "%A%").notLike("name", "%N%")
                .notLike("name", "%U%").notLike("name", "%W%").notLike("code", "%BJ%"));
        STOCK_TRACK_MAP.clear();
        AutomaticPlankTrading.STOCK_AUTO_PLANK_FILTER_MAP.clear();
        AutomaticPlankTrading.PLANK_MONITOR.clear();
        stocks.forEach(e -> {
            if ((e.getShareholding() || e.getTrack())) {
                STOCK_TRACK_MAP.put(e.getName(), e);
            } else if (e.getTransactionAmount().doubleValue() > plankConfig.getStockTurnoverThreshold()
                    && (Objects.isNull(e.getBuyTime()) || !DateUtils.isSameDay(new Date(), e.getBuyTime()))) {
                // 过滤掉成交额小于plankConfig.getStockTurnoverFilter()的股票,
                if (plankConfig.getAutomaticPlankTop5Bk()) {
                    if (CollectionUtils.isNotEmpty(StockProcessor.TOP5_BK.values())) {
                        String bk = StockProcessor.TOP5_BK.keySet().stream().filter(v -> Objects.nonNull(e.getClassification()) &&
                                e.getClassification().contains(v)).findFirst().orElse(null);
                        if (StringUtils.isNotEmpty(bk)) {
                            //log.warn("{}板块的{}加入一级缓存", StockProcessor.TOP5_BK.get(bk).getName(), e.getName());
                            AutomaticPlankTrading.STOCK_AUTO_PLANK_FILTER_MAP.put(e.getCode(), e);
                        }
                    }
                } else {
                    AutomaticPlankTrading.STOCK_AUTO_PLANK_FILTER_MAP.put(e.getCode(), e);
                }
            }
            STOCK_ALL_MAP.put(e.getCode(), e.getName());
        });
        if (plankConfig.getAutomaticPlankTrading()) {
            log.warn("加载[{}]支股票,自动打板二级缓存[{}]支,开启自动打板:{},是否只打涨幅Top5板块的成分股:{}",
                    stocks.size(), AutomaticPlankTrading.PLANK_MONITOR.size(), plankConfig.getAutomaticPlankTrading(),
                    plankConfig.getAutomaticPlankTop5Bk());
        }
    }

    /**
     * 开盘,初始化版块基本数据
     */
    @Scheduled(cron = "0 30 9 * * ?")
    private void opening() {
        // 更新行业版块，概念版块涨幅信息
        this.updateBkRealTimeData();
        this.updateStockCache();
    }

    /**
     * 每3秒更新一次版块涨跌幅
     */
    @Scheduled(cron = "*/3 * * * * ?")
    private void updateBkCache() {
        if (AutomaticTrading.isTradeTime()) {
            // 更新行业版块，概念版块涨幅信息
            this.updateBkRealTimeData();
        }
    }

    /**
     * 更新版块实时数据
     */
    private void updateBkRealTimeData() {
        stockProcessor.updateBk();
        stockProcessor.updateTop5IncreaseRateBk();
    }

    /**
     * 每2分钟更新每支股票的成交额,开盘6分钟内不更新,开盘快速封板的票当日成交额可能比较少
     * 成交额满足阈值的会放入 STOCK_AUTO_PLANK_FILTER_MAP 去检测涨幅
     */
    @Scheduled(cron = "0 */2 * * * ?")
    private void updateStockRealTimeData() throws InterruptedException {
        Date openingTime = new Date();
        openingTime = DateUtils.setHours(openingTime, 9);
        openingTime = DateUtils.setMinutes(openingTime, 30);
        if (AutomaticTrading.isTradeTime() && DateUtils.addMinutes(new Date(), -6).getTime() > openingTime.getTime()) {
            List<List<String>> partition = Lists.partition(Lists.newArrayList(Barbarossa.STOCK_ALL_MAP.keySet()), 300);
            CountDownLatch countDownLatch = new CountDownLatch(partition.size());
            for (List<String> list : partition) {
                executorService.submit(() -> stockProcessor.run(list, countDownLatch));
            }
            countDownLatch.await();
            this.updateStockCache();
        }
    }

    /**
     * 此方法主要用来预警接近建仓价的股票
     * 实时监测数据 显示股票实时涨跌幅度，最高，最低价格，主力流入
     * 想要监测哪些股票需要手动在数据库stock表更改track字段为true
     * 我一般会选择趋势股或赛道股，所以默认把MA10作为建仓基准价格，可以手动修改stock.purchase_type字段来设置，5-则以MA5为基准价格,最多MA20
     * 股价除权之后需要重新爬取交易数据，算均价就不准了
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void monitor() {
        if (plankConfig.getEnableMonitor() && AutomaticTrading.isTradeTime() &&
                !monitoring.get() && STOCK_TRACK_MAP.size() > 0) {
            monitoring.set(true);
            executorService.submit(this::monitorStock);
            executorService.submit(this::queryMainFundData);
        }
    }

    private void monitorStock() {
        try {
            List<StockRealTimePrice> realTimePrices = new ArrayList<>();
            while (AutomaticTrading.isTradeTime()) {
                List<Stock> stocks = stockMapper.selectList(new LambdaQueryWrapper<Stock>()
                        .in(Stock::getName, STOCK_TRACK_MAP.keySet()));
                for (Stock stock : stocks) {
                    // 默认把MA10作为建仓基准价格
                    int purchaseType = Objects.isNull(stock.getPurchaseType()) || stock.getPurchaseType() == 0 ? 10
                            : stock.getPurchaseType();
                    List<DailyRecord> dailyRecords = dailyRecordMapper.selectList(new LambdaQueryWrapper<DailyRecord>()
                            .eq(DailyRecord::getCode, stock.getCode())
                            .ge(DailyRecord::getDate, DateUtils.addDays(new Date(), -purchaseType * 3))
                            .orderByDesc(DailyRecord::getDate));
                    if (dailyRecords.size() < purchaseType) {
                        log.error("{}的交易数据不完整,不足{}个交易日数据,请先爬取交易数据", stock.getCode(), stock.getPurchaseType());
                        continue;
                    }
                    StockRealTimePrice stockRealTimePrice = stockProcessor.getStockRealTimePriceByCode(stock.getCode());
                    double v = stockRealTimePrice.getCurrentPrice();
                    List<BigDecimal> collect = dailyRecords.subList(0, purchaseType - 1).stream()
                            .map(DailyRecord::getClosePrice).collect(Collectors.toList());
                    collect.add(new BigDecimal(v).setScale(2, RoundingMode.HALF_UP));
                    double ma = collect.stream().collect(Collectors.averagingDouble(BigDecimal::doubleValue));
                    // 如果手动设置了purchasePrice，则以stock.purchasePrice 和均线价格 2个当中更低的价格为基准价
                    if (Objects.nonNull(stock.getPurchasePrice()) && stock.getPurchasePrice().doubleValue() > 0) {
                        ma = Math.min(stock.getPurchasePrice().doubleValue(), ma);
                    }
                    BigDecimal maPrice = new BigDecimal(ma).setScale(2, RoundingMode.HALF_UP);
                    double purchaseRate = (double) Math.round(((maPrice.doubleValue() - v) / v) * 100) / 100;
                    stockRealTimePrice.setName(stock.getName());
                    stockRealTimePrice.setMainFund(MAIN_FUND_DATA_MAP.containsKey(stock.getName())
                            ? MAIN_FUND_DATA_MAP.get(stock.getName()).getF62() / W : 0);
                    stockRealTimePrice.setPurchasePrice(maPrice);
                    stockRealTimePrice.setPurchaseRate((int) (purchaseRate * 100));
                    realTimePrices.add(stockRealTimePrice);
                }
                Collections.sort(realTimePrices);
                System.out.println("\n\n\n");
                log.error("------------------------ 主力净流入Top10 --------------------------");
                List<StockMainFundSample> topTen = new ArrayList<>();
                for (int i = 0; i < Math.min(MAIN_FUND_DATA.size(), 10); i++) {
                    topTen.add(MAIN_FUND_DATA.get(i));
                }
                log.warn(collectionToString(topTen.stream().map(e -> e.getF14() + e.getF3()).collect(Collectors.toList())));
                log.error("------------------------- 板块涨幅>2Top5 --------------------------");
                ArrayList<Bk> bks = Lists.newArrayList(StockProcessor.TOP5_BK.values());
                Collections.sort(bks);
                log.warn(collectionToString(bks.stream().map(e -> e.getName() + ":" + e.getIncreaseRate()).collect(Collectors.toList())));
                List<StockRealTimePrice> shareholding = realTimePrices.stream().filter(e -> STOCK_TRACK_MAP.containsKey(e.getName()) &&
                        STOCK_TRACK_MAP.get(e.getName()).getShareholding()).collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(shareholding)) {
                    log.error("------------------------------- 持仓 ------------------------------");
                    shareholding.forEach(this::print);
                }
                realTimePrices.removeIf(e -> STOCK_TRACK_MAP.containsKey(e.getName()) && STOCK_TRACK_MAP.get(e.getName()).getShareholding());
                List<StockRealTimePrice> stockRealTimePrices = realTimePrices.stream().filter(e ->
                        e.getPurchaseRate() >= -2).collect(Collectors.toList());
                if (CollectionUtils.isNotEmpty(stockRealTimePrices)) {
                    log.error("------------------------------ Track ------------------------------");
                    stockRealTimePrices.forEach(this::print);
                }
                List<HoldShares> buyStocks = holdSharesMapper.selectList(new LambdaQueryWrapper<HoldShares>()
                        .ge(HoldShares::getBuyTime, DateUtil.beginOfDay(new Date()))
                        .le(HoldShares::getBuyTime, DateUtil.endOfDay(new Date()))
                        .ne(HoldShares::getAutomaticTradingType, AutomaticTradingEnum.MANUAL.name()));
                if (CollectionUtils.isNotEmpty(buyStocks)) {
                    log.error("----------------------- 自动打板,排单金额:{} -----------------------",
                            AutomaticTrading.TODAY_COST_MONEY.intValue());
                    log.warn("{}", collectionToString(buyStocks.stream().map(HoldShares::getName).collect(Collectors.toSet())));
                    if (plankConfig.getAutomaticPlankTrading() && automaticPlankTrading.openAutoPlank()) {
                        log.warn("打板监测:{}", collectionToString(AutomaticPlankTrading.PLANK_MONITOR.values().stream()
                                .map(Stock::getName).collect(Collectors.toList())));
                    }
                }
                if (CollectionUtils.isNotEmpty(AutomaticTrading.UNDER_MONITORING.values())) {
                    log.error("--------------------------- 自定义交易监测 --------------------------");
                    log.warn("{}", collectionToString(AutomaticTrading.UNDER_MONITORING.values().stream()
                            .map(Stock::getName).collect(Collectors.toList())));
                }
                realTimePrices.clear();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            monitoring.set(false);
        }
    }

    private void print(StockRealTimePrice stockRealTimePrices) {
        if (stockRealTimePrices.getIncreaseRate() > 0) {
            Barbarossa.log.error(convertLog(stockRealTimePrices));
        } else {
            Barbarossa.log.warn(convertLog(stockRealTimePrices));
        }
    }

    /**
     * 查询主力实时流入数据
     */
    private void queryMainFundData() {
        while (AutomaticTrading.isTradeTime()) {
            try {
                String body = HttpUtil.getHttpGetResponseString(plankConfig.getMainFundUrl(), null);
                JSONArray array = JSON.parseObject(body).getJSONObject("data").getJSONArray("diff");
                ArrayList<StockMainFundSample> tmpList = new ArrayList<>();
                array.forEach(e -> {
                    try {
                        StockMainFundSample mainFundSample = JSONObject.parseObject(e.toString(), StockMainFundSample.class);
                        tmpList.add(mainFundSample);
                        MAIN_FUND_DATA_MAP.put(mainFundSample.getF14(), mainFundSample);
                    } catch (JSONException ignored) {
                    } catch (Exception exception) {
                        exception.printStackTrace();
                    }
                });
                MAIN_FUND_DATA.clear();
                MAIN_FUND_DATA.addAll(tmpList.stream().filter(e -> e.getF62() > 100000000).collect(Collectors.toList()));
                Thread.sleep(3000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 15点后读取当日交易数据
     */
    @Scheduled(cron = "0 1 15 * * ?")
    private void analyzeData() {
        try {
            CountDownLatch countDownLatch = new CountDownLatch(Barbarossa.STOCK_ALL_MAP.size());
            dailyRecordProcessor.run(Barbarossa.STOCK_ALL_MAP, countDownLatch);
            this.resetStockData();
            countDownLatch.await();
            log.warn("每日涨跌明细、成交额、MA5、MA10、MA20更新完成");
            executorService.submit(stockProcessor::updateStockBkInfo);
            // 更新 外资+基金 持仓 只更新到最新季度报告的汇总表上 基金季报有滞后性，外资持仓则是实时计算，每天更新的
            executorService.submit(stockProcessor::updateForeignFundShareholding);
            executorService.submit(() -> {
                // 分析连板数据
                analyzePlank.analyzePlank();
                // 分析主力流入数据
                analyzePlank.analyzeMainFund();
                // 分析日k均线多头排列的股票
                screeningStocks.movingAverageRise();
                // 分析上升趋势的股票，周k均线多头排列
                screeningStocks.upwardTrend();
                // 分析爆量回踩
                screeningStocks.explosiveVolumeBack();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 重置stock表,持仓表数据
     */
    private void resetStockData() {
        stockMapper.update(Stock.builder().plankNumber(0).automaticTradingType(AutomaticTradingEnum.CANCEL.name())
                .suckTriggerPrice(new BigDecimal(0)).buyAmount(0).build(), new LambdaUpdateWrapper<>());
        holdSharesMapper.update(HoldShares.builder().todayPlank(false).build(), new LambdaUpdateWrapper<HoldShares>()
                .eq(HoldShares::getClearance, false));
    }

    private String convertLog(StockRealTimePrice realTimePrice) {
        return realTimePrice.getName() + (realTimePrice.getName().length() == 3 ? "  " : "") +
                ">高:" + realTimePrice.getHighestPrice() + "|现:" + realTimePrice.getCurrentPrice() +
                "|低:" + realTimePrice.getLowestPrice() + "|" + realTimePrice.getIncreaseRate() + "%";
    }

}
