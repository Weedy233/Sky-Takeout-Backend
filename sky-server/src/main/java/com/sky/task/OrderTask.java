package com.sky.task;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.sky.entity.Orders;
import com.sky.mapper.OrderMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class OrderTask {
    @Autowired
    OrderMapper ordersMapper;

    @Scheduled(cron = "0 * * * * ?")
    public void ProcessTimeoutOrder() {
        log.info("处理超时未付款订单：{}", LocalDateTime.now());

        LocalDateTime ThresholdTime = LocalDateTime.now().minusMinutes(15);
        List<Orders> ordersList = ordersMapper.getByStatusAndOrdersLT(Orders.PENDING_PAYMENT, ThresholdTime);

        if (ordersList == null || ordersList.size() == 0) {
            return;
        }

        for (Orders orders: ordersList) {
            orders.setStatus(Orders.CANCELLED);
            orders.setCancelReason("订单超时，自动取消");
            orders.setCancelTime(LocalDateTime.now());
            ordersMapper.update(orders);
        }
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void ProcessDeliveryOrder() {
        log.info("定时处理已派送订单：{}", LocalDateTime.now());

        List<Orders> ordersList = ordersMapper.getByStatusAndOrdersLT(Orders.DELIVERY_IN_PROGRESS, LocalDateTime.now());

        if (ordersList == null || ordersList.size() == 0) {
            return;
        }
        
        for (Orders orders: ordersList) {
            orders.setStatus(Orders.COMPLETED);
            ordersMapper.update(orders);
        }
    }
}
