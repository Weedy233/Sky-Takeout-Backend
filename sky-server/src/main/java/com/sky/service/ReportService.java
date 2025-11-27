package com.sky.service;

import java.time.LocalDate;

import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;

public interface ReportService {

    /**
     * 获取指定日期区间内的营业额数据
     * @param end 
     * @param begin 
     * @return
     */
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end);

    /**
     * 获取指定日期区间内的用户数据
     * @param begin
     * @param end
     * @return
     */
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end);

    /**
     * 获取指定日期区间内的菜品销量前十数据
     * @param begin
     * @param end
     * @return
     */
    public SalesTop10ReportVO getSaleTop10(LocalDate begin, LocalDate end);

}
