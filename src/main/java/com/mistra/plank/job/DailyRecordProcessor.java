package com.mistra.plank.job;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.mistra.plank.common.config.PlankConfig;
import com.mistra.plank.common.util.HttpUtil;
import com.mistra.plank.dao.DailyRecordMapper;
import com.mistra.plank.dao.StockMapper;
import com.mistra.plank.model.dto.StockRealTimePrice;
import com.mistra.plank.model.entity.DailyRecord;
import com.mistra.plank.model.entity.Stock;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * @author Mistra @ Version: 1.0
 * @ Time: 2021/11/18 22:09
 * @ Description: 更新股票每日成交数据
 * @ Copyright (c) Mistra,All Rights Reserved
 * @ Github: https://github.com/MistraR
 * @ CSDN: https://blog.csdn.net/axela30w
 */
@Slf4j
@Component
public class DailyRecordProcessor {

    private final DailyRecordMapper dailyRecordMapper;
    private final StockMapper stockMapper;
    @Lazy
    @Autowired
    private StockProcessor stockProcessor;
    private final PlankConfig plankConfig;

    public DailyRecordProcessor(DailyRecordMapper dailyRecordMapper, StockMapper stockMapper, PlankConfig plankConfig) {
        this.dailyRecordMapper = dailyRecordMapper;
        this.stockMapper = stockMapper;
        this.plankConfig = plankConfig;
    }

    /**
     * 更新某支股票最近recentDayNumber天的交易数据
     */
    public void updateByName(String name, int recentDayNumber) {
        Stock stock = stockMapper.selectOne(new LambdaQueryWrapper<Stock>().eq(Stock::getName, name));
        if (Objects.isNull(stock)) {
            return;
        }
        dailyRecordMapper.delete(new LambdaQueryWrapper<DailyRecord>().eq(DailyRecord::getCode, stock.getCode()));
        String url = plankConfig.getXueQiuStockDetailUrl().replace("{code}", stock.getCode())
                .replace("{time}", String.valueOf(System.currentTimeMillis()))
                .replace("{recentDayNumber}", String.valueOf(recentDayNumber));
        String body = HttpUtil.getHttpGetResponseString(url, plankConfig.getXueQiuCookie());
        JSONObject data = JSON.parseObject(body).getJSONObject("data");
        JSONArray list = data.getJSONArray("item");
        if (CollectionUtils.isNotEmpty(list)) {
            JSONArray array;
            for (Object o : list) {
                array = (JSONArray) o;
                DailyRecord dailyRecord = new DailyRecord();
                Date date = new Date(array.getLongValue(0));
                dailyRecord.setDate(date);
                dailyRecord.setCode(stock.getCode());
                dailyRecord.setName(stock.getName());
                dailyRecord.setOpenPrice(BigDecimal.valueOf(array.getDoubleValue(2)));
                dailyRecord.setHighest(BigDecimal.valueOf(array.getDoubleValue(3)));
                dailyRecord.setLowest(BigDecimal.valueOf(array.getDoubleValue(4)));
                dailyRecord.setClosePrice(BigDecimal.valueOf(array.getDoubleValue(5)));
                dailyRecord.setIncreaseRate(BigDecimal.valueOf(array.getDoubleValue(7)));
                dailyRecord.setAmount(array.getLongValue(9) / 10000);
                if (DateUtils.isSameDay(date, new Date())) {
                    StockRealTimePrice stockRealTimePriceByCode = stockProcessor.getStockRealTimePriceByCode(stock.getCode());
                    dailyRecord.setPlank(stockRealTimePriceByCode.isPlank());
                }
                dailyRecordMapper.insert(dailyRecord);
            }
        }
    }

    public void run(HashMap<String, String> map, CountDownLatch countDownLatch) {
        Integer count = dailyRecordMapper.selectCount(new QueryWrapper<DailyRecord>().ge("date", checkDailyRecord()));
        if (count > 0) {
            while (countDownLatch.getCount() > 0) {
                countDownLatch.countDown();
            }
            log.warn("股票交易数据已更新到最新交易日");
            return;
        }
        for (Map.Entry<String, String> entry : map.entrySet()) {
            Barbarossa.executorService.submit(() -> run(entry.getKey(), entry.getValue(), countDownLatch));
        }
    }

    public void run(String code, String name, CountDownLatch countDownLatch) {
        try {
            String url = plankConfig.getXueQiuStockDetailUrl().replace("{code}", code)
                    .replace("{time}", String.valueOf(System.currentTimeMillis()))
                    .replace("{recentDayNumber}", String.valueOf(plankConfig.getRecentDayNumber()));
            String body = HttpUtil.getHttpGetResponseString(url, plankConfig.getXueQiuCookie());
            JSONObject data = JSON.parseObject(body).getJSONObject("data");
            JSONArray list = data.getJSONArray("item");
            if (CollectionUtils.isNotEmpty(list)) {
                JSONArray array;
                for (Object o : list) {
                    array = (JSONArray) o;
                    DailyRecord dailyRecord = new DailyRecord();
                    Date date = new Date(array.getLongValue(0));
                    dailyRecord.setDate(date);
                    dailyRecord.setCode(code);
                    dailyRecord.setName(name);
                    dailyRecord.setOpenPrice(BigDecimal.valueOf(array.getDoubleValue(2)));
                    dailyRecord.setHighest(BigDecimal.valueOf(array.getDoubleValue(3)));
                    dailyRecord.setLowest(BigDecimal.valueOf(array.getDoubleValue(4)));
                    dailyRecord.setClosePrice(BigDecimal.valueOf(array.getDoubleValue(5)));
                    dailyRecord.setIncreaseRate(BigDecimal.valueOf(array.getDoubleValue(7)));
                    dailyRecord.setAmount(array.getLongValue(9) / 10000);
                    if (DateUtils.isSameDay(date, new Date())) {
                        StockRealTimePrice stockRealTimePriceByCode = stockProcessor.getStockRealTimePriceByCode(code);
                        dailyRecord.setPlank(stockRealTimePriceByCode.isPlank());
                    }
                    dailyRecordMapper.insert(dailyRecord);
                }
            }
        } catch (Exception e) {
        } finally {
            if (Objects.nonNull(countDownLatch)) {
                countDownLatch.countDown();
            }
        }
    }

    /**
     * 查询上一个交易日日期
     *
     * @return 上一个交易日日期
     */
    private Date checkDailyRecord() {
        String url = plankConfig.getXueQiuStockDetailUrl().replace("{code}", "SH600519")
                .replace("{time}", String.valueOf(System.currentTimeMillis())).replace("{recentDayNumber}", "1");
        String body = HttpUtil.getHttpGetResponseString(url, plankConfig.getXueQiuCookie());
        JSONObject data = JSON.parseObject(body).getJSONObject("data");
        JSONArray list = data.getJSONArray("item");
        if (CollectionUtils.isNotEmpty(list)) {
            JSONArray array;
            for (Object o : list) {
                array = (JSONArray) o;
                return new Date(array.getLongValue(0));
            }
        }
        return new Date();
    }
}
