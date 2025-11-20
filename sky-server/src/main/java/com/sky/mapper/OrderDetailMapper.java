package com.sky.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.sky.entity.OrderDetail;

@Mapper
public interface OrderDetailMapper {

    /**
     * 批量插入订单详情
     * @param orderDetailsList
     */
    void insertBatch(List<OrderDetail> orderDetailsList);

    /**
     * 查询订单 id 下的所有订单
     * @param orderId
     * @return
     */
    @Select("select * from order_detail where order_id = #{orderId}")
    List<OrderDetail> getByOrderId(Long orderId);
}
