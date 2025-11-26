package com.sky.service;

import java.time.LocalDate;

import com.sky.vo.TurnoverReportVO;

public interface ReportService {

    /**
     * 获取指定日期区间内的营业额数据
     * @param end 
     * @param begin 
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end);

}
