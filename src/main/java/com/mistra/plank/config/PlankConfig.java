package com.mistra.plank.config;


import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @author Mistra
 * @ Version: 1.0
 * @ Time: 2021/11/18 21:44
 * @ Description:
 * @ Copyright (c) Mistra,All Rights Reserved.
 * @ Github: https://github.com/MistraR
 * @ CSDN: https://blog.csdn.net/axela30w
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "plank")
public class PlankConfig {

    /**
     * 雪球 Cookie
     */
    private String xueQiuCookie;

    /**
     * 雪球 获取所有股票信息，每日更新成交量
     */
    private String xueQiuAllStockUrl;

    /**
     * 雪球 获取某只股票最近recentDayNumber天的每日涨跌记录url
     */
    private String xueQiuStockDetailUrl;

    /**
     * 东财 抓取每日龙虎榜数据，只取净买入额前20
     */
    private String dragonListUrl;

    /**
     * 东财 抓取从某天以来的龙虎榜数据
     */
    private Long dragonListTime;

    /**
     * 分析首板一进二，二板二进三胜率 开始日期  2021-11-01
     */
    private Long analyzeTime;

    /**
     * 雪球 获取某只股票最近多少天的记录
     */
    private Integer recentDayNumber;

    /**
     * 开始操盘日期
     */
    private Long beginDay;

    /**
     * 起始资金
     */
    private Integer funds;

    /**
     * 资金分层数
     */
    private Integer fundsPart;

    /**
     * 每日仓位层数上限
     */
    private Integer fundsPartLimit;

    /**
     * 止盈清仓比率
     */
    private BigDecimal profitUpperRatio;

    /**
     * 阶段止盈减半仓比率
     */
    private BigDecimal profitQuarterRatio;

    /**
     * 阶段止盈减半仓比率
     */
    private BigDecimal profitHalfRatio;

    /**
     * 阶段止盈回车清仓比率
     */
    private BigDecimal profitClearanceRatio;

    /**
     * 止损比率
     */
    private BigDecimal deficitRatio;

    /**
     * 止损均线
     */
    private Integer deficitMovingAverage;

    /**
     * 介入比率下限 2日涨幅
     */
    private BigDecimal joinIncreaseRatioLowerLimit;

    /**
     * 介入比率上限 6日涨幅
     */
    private BigDecimal joinIncreaseRatioUpperLimit;

    /**
     * 可打板涨幅比率
     */
    private BigDecimal buyRatioLimit;

    /**
     * 股价上限
     */
    private Integer stockPriceUpperLimit;

    /**
     * 股价下限
     */
    private Integer stockPriceLowerLimit;

    /**
     * 股价下限
     */
    private Integer clearanceDay;

    /**
     * 可打板涨幅比率
     */
    private BigDecimal buyPlankRatioLimit;

    /**
     * 上升趋势样本名称，逗号分隔
     */
    private String sample;
    /**
     * 样本入选日期
     */
    private Long sampleDay;
    /**
     * 样本入选第5个交易日日期
     */
    private Long sampleFiveDay;
    /**
     * 样本入选第10个交易日日期
     */
    private Long sampleTenDay;
    /**
     * 样本入选第15个交易日日期
     */
    private Long sampleFifteenDay;
    /**
     * 样本入选第20个交易日日期
     */
    private Long sampleTwentyDay;
    /**
     * 样本入选第25个交易日日期
     */
    private Long sampleTwentyFiveDay;
    /**
     * 样本入选第30个交易日日期
     */
    private Long sampleThirtyDay;

    /**
     * 已加入自选的20CM股票
     */
    private String gemPlankStock;
    /**
     * 已加入自选的20CM2板股票
     */
    private String gemPlankStockTwice;
    /**
     * 已加入自选的5连板股票
     */
    private String fivePlank;

}
