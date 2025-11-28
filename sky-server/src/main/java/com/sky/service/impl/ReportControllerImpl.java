package com.sky.service.impl;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sky.dto.GoodsSalesDTO;
import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.UserMapper;
import com.sky.service.ReportService;
import com.sky.service.WorkspaceService;
import com.sky.vo.BusinessDataVO;
import com.sky.vo.SalesTop10ReportVO;
import com.sky.vo.TurnoverReportVO;
import com.sky.vo.UserReportVO;

@Service
public class ReportControllerImpl implements ReportService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WorkspaceService workspaceService;

    /**
     * 获取指定日期区间内的营业额数据
     * 
     * @param end
     * @param begin
     * @return
     */
    @Override
    public TurnoverReportVO getTurnoverStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> localDateList = new ArrayList<>();

        while (begin.isBefore(end.plusDays(1))) {
            localDateList.add(begin);
            begin = begin.plusDays(1);
        }

        List<Double> turnoverList = new ArrayList<>();
        for (LocalDate localDate : localDateList) {
            LocalDateTime beginTime = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

            Map<String, Object> map = new HashMap<>();
            map.put("status", Orders.COMPLETED);
            map.put("beginTime", beginTime);
            map.put("endTime", endTime);

            turnoverList.add(orderMapper.sumByMap(map));
        }

        return TurnoverReportVO.builder()
                .dateList(StringUtils.join(localDateList, ","))
                .turnoverList(StringUtils.join(turnoverList, ","))
                .build();
    }

    /**
     * 获取指定日期区间内的用户数据
     * 
     * @param begin
     * @param end
     * @return
     */
    @Override
    public UserReportVO getUserStatistics(LocalDate begin, LocalDate end) {
        List<LocalDate> localDateList = new ArrayList<>();

        while (begin.isBefore(end.plusDays(1))) {
            localDateList.add(begin);
            begin = begin.plusDays(1);
        }

        List<Integer> totalUserList = new ArrayList<>();
        List<Integer> newUserList = new ArrayList<>();

        for (LocalDate localDate : localDateList) {
            LocalDateTime beginTime = LocalDateTime.of(localDate, LocalTime.MIN);
            LocalDateTime endTime = LocalDateTime.of(localDate, LocalTime.MAX);

            Map<String, Object> map = new HashMap<>();

            map.put("endTime", endTime);
            Integer totalCount = userMapper.getCountByMap(map);
            totalUserList.add(totalCount);

            map.put("beginTime", beginTime);
            Integer newCount = userMapper.getCountByMap(map);
            newUserList.add(newCount);
        }

        return UserReportVO.builder()
                .dateList(StringUtils.join(localDateList, ","))
                .totalUserList(StringUtils.join(totalUserList, ","))
                .newUserList(StringUtils.join(newUserList, ","))
                .build();
    }

    /**
     * 获取指定日期区间内的菜品销量前十数据
     * 
     * @param begin
     * @param end
     * @return
     */
    @Override
    public SalesTop10ReportVO getSaleTop10(LocalDate begin, LocalDate end) {
        LocalDateTime beginTime = LocalDateTime.of(begin, LocalTime.MIN);
        LocalDateTime endTime = LocalDateTime.of(end, LocalTime.MAX);
        List<GoodsSalesDTO> salesTop10 = orderMapper.getSaleTop10(beginTime, endTime);

        List<String> nameList = salesTop10.stream()
                .map(GoodsSalesDTO::getName)
                .collect(Collectors.toList());
        List<Integer> numberList = salesTop10.stream()
                .map(GoodsSalesDTO::getNumber)
                .collect(Collectors.toList());

        return SalesTop10ReportVO.builder()
                .nameList(StringUtils.join(nameList, ","))
                .numberList(StringUtils.join(numberList, ","))
                .build();
    }

    /**
     * 导出运营数据报表
     */
    @Override
    public void exportBusinessData(HttpServletResponse response) {
        LocalDate beginDay = LocalDate.now().minusDays(30);
        LocalDate endDay = LocalDate.now().minusDays(1);
        BusinessDataVO businessData = workspaceService.getBusinessData(
                LocalDateTime.of(beginDay, LocalTime.MIN),
                LocalDateTime.of(endDay, LocalTime.MAX));

        InputStream in = this.getClass().getClassLoader().getResourceAsStream(
            "template/运营数据报表模板.xlsx");
        
        try {
            XSSFWorkbook excel = new XSSFWorkbook(in);

            XSSFSheet sheet = excel.getSheetAt(0);

            sheet.getRow(1).getCell(1).setCellValue("时间：" + beginDay + "至" + endDay);

            XSSFRow row = sheet.getRow(3);
            row.getCell(2).setCellValue(businessData.getTurnover());
            row.getCell(4).setCellValue(businessData.getOrderCompletionRate());
            row.getCell(6).setCellValue(businessData.getNewUsers());

            row = sheet.getRow(4);
            row.getCell(2).setCellValue(businessData.getValidOrderCount());
            row.getCell(4).setCellValue(businessData.getUnitPrice());

            for (int i = 0; i < 30; i++) {
                LocalDate day = beginDay.plusDays(i);
                BusinessDataVO data = workspaceService.getBusinessData(
                        LocalDateTime.of(day, LocalTime.MIN),
                        LocalDateTime.of(day, LocalTime.MAX));

                row = sheet.getRow(7 + i);
                row.getCell(1).setCellValue(day.toString());
                row.getCell(2).setCellValue(data.getTurnover());
                row.getCell(3).setCellValue(data.getValidOrderCount());
                row.getCell(4).setCellValue(data.getOrderCompletionRate());
                row.getCell(5).setCellValue(data.getUnitPrice());
                row.getCell(6).setCellValue(data.getNewUsers());
            }

            ServletOutputStream out = response.getOutputStream();
            excel.write(out);

            out.close();
            excel.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

}
