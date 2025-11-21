package com.sky.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.sky.constant.MessageConstant;
import com.sky.context.BaseContext;
import com.sky.dto.OrdersCancelDTO;
import com.sky.dto.OrdersConfirmDTO;
import com.sky.dto.OrdersPageQueryDTO;
import com.sky.dto.OrdersPaymentDTO;
import com.sky.dto.OrdersRejectionDTO;
import com.sky.dto.OrdersSubmitDTO;
import com.sky.entity.AddressBook;
import com.sky.entity.OrderDetail;
import com.sky.entity.Orders;
import com.sky.entity.ShoppingCart;
import com.sky.entity.User;
import com.sky.exception.AddressBookBusinessException;
import com.sky.exception.OrderBusinessException;
import com.sky.exception.ShoppingCartBusinessException;
import com.sky.mapper.AddressBookMapper;
import com.sky.mapper.OrderDetailMapper;
import com.sky.mapper.OrderMapper;
import com.sky.mapper.ShoppingCartMapper;
import com.sky.mapper.UserMapper;
import com.sky.result.PageResult;
import com.sky.service.OrderService;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class OrderServiceImpl implements OrderService{

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private OrderDetailMapper orderDetailMapper;
    @Autowired
    private AddressBookMapper addressBookMapper;
    @Autowired
    private ShoppingCartMapper shoppingCartMapper;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private WeChatPayUtil weChatPayUtil;

    @Override
    @Transactional
    public OrderSubmitVO submitOrder(OrdersSubmitDTO ordersSubmitDTO) {
        // 处理各种业务异常
        // 1. 地址簿为空
        AddressBook addressBook = addressBookMapper.getById(ordersSubmitDTO.getAddressBookId());
        if (addressBook == null) {
            throw new AddressBookBusinessException(MessageConstant.ADDRESS_BOOK_IS_NULL);
        }
        // 2. 购物车数据为空
        Long userId = BaseContext.getCurrentId();

        ShoppingCart shoppingCart = new ShoppingCart();
        shoppingCart.setUserId(userId);

        List<ShoppingCart> shoppingCartList = shoppingCartMapper.list(shoppingCart);
        
        if (shoppingCartList == null || shoppingCartList.size() == 0) {
            throw new ShoppingCartBusinessException(MessageConstant.SHOPPING_CART_IS_NULL);
        }
        
        // 向订单表插入一条数据
        Orders orders = new Orders();
        BeanUtils.copyProperties(ordersSubmitDTO, orders);
        orders.setOrderTime(LocalDateTime.now());
        orders.setPayStatus(Orders.UN_PAID);
        orders.setStatus(Orders.PENDING_PAYMENT);
        orders.setNumber(String.valueOf(System.currentTimeMillis()));
        orders.setPhone(addressBook.getPhone());
        orders.setConsignee(addressBook.getConsignee());
        orders.setUserId(userId);

        orderMapper.insert(orders);

        // 向订单明细表插入 n 条数据
        List<OrderDetail> orderDetailsList = new ArrayList<OrderDetail>();
        for (ShoppingCart cart: shoppingCartList) {
            OrderDetail orderDetail = new OrderDetail();
            BeanUtils.copyProperties(cart, orderDetail);
            orderDetail.setOrderId(orders.getId());
            orderDetailsList.add(orderDetail);
        }
        orderDetailMapper.insertBatch(orderDetailsList);

        // 清空购物车
        shoppingCartMapper.deleteByUserId(userId);

        // 封装 VO 返回数据
        OrderSubmitVO orderSubmitVO = OrderSubmitVO.builder()
                    .id(orders.getId())
                    .orderNumber(orders.getNumber())
                    .orderAmount(orders.getAmount())
                    .orderTime(LocalDateTime.now())
                    .build();

        return orderSubmitVO;
    }

    /**
     * 订单支付
     *
     * @param ordersPaymentDTO
     * @return
     */
    public OrderPaymentVO payment(OrdersPaymentDTO ordersPaymentDTO) throws Exception {
        // 当前登录用户id
        Long userId = BaseContext.getCurrentId();
        User user = userMapper.getById(userId);

        //调用微信支付接口，生成预支付交易单
        JSONObject jsonObject = weChatPayUtil.pay(
                ordersPaymentDTO.getOrderNumber(), // 商户订单号
                new BigDecimal(0.01), // 支付金额，单位 元
                "苍穹外卖订单", // 商品描述
                user.getOpenid() // 微信用户的openid
        );

        if (jsonObject.getString("code") != null && jsonObject.getString("code").equals("ORDERPAID")) {
            throw new OrderBusinessException("该订单已支付");
        }

        OrderPaymentVO vo = jsonObject.toJavaObject(OrderPaymentVO.class);
        vo.setPackageStr(jsonObject.getString("package"));

        // --- mark: 以下内容为 AI 添加 ---
        // 检查是否为模拟支付，如果是则延迟触发支付成功回调
        if (isMockPayment(jsonObject)) {
            // 延迟3秒后自动触发支付成功回调，模拟用户支付过程
            new Thread(() -> {
                try {
                    Thread.sleep(3000); // 延迟3秒
                    log.info("=== 自动触发模拟支付成功回调 ===");
                    paySuccess(ordersPaymentDTO.getOrderNumber());
                    log.info("=== 模拟支付成功，订单状态已更新 ===");
                } catch (Exception e) {
                    log.error("自动触发支付回调失败", e);
                }
            }).start();
        }
        // --- mark: 以上内容为 AI 添加 ---

        return vo;
    }

    // --- mark: 以下内容为 AI 添加 ---
    /**
     * 检查是否为模拟支付
     * @param jsonObject
     * @return
     */
    private boolean isMockPayment(JSONObject jsonObject) {
        String packageStr = jsonObject.getString("package");
        return packageStr != null && packageStr.contains("MOCK_PREPAY_");
    }
    // --- mark: 以上内容为 AI 添加 ---

    /**
     * 支付成功，修改订单状态
     *
     * @param outTradeNo
     */
    public void paySuccess(String outTradeNo) {

        // 根据订单号查询订单
        Orders ordersDB = orderMapper.getByNumber(outTradeNo);

        // 根据订单id更新订单的状态、支付方式、支付状态、结账时间
        Orders orders = Orders.builder()
                .id(ordersDB.getId())
                .status(Orders.TO_BE_CONFIRMED)
                .payStatus(Orders.PAID)
                .checkoutTime(LocalDateTime.now())
                .build();

        orderMapper.update(orders);
    }

    /**
     * 根据订单 id 查询订单详情
     * @param id
     * @return
     */
    @Override
    public OrderVO details(Long id) {
        OrderVO orderVO = new OrderVO();
        Orders orders = orderMapper.getById(id);

        AddressBook addressBook = addressBookMapper.getById(orders.getAddressBookId());
        orders.setAddress(addressBook.getDetail());

        BeanUtils.copyProperties(orders, orderVO);

        List<OrderDetail> orderDetailsList = orderDetailMapper.getByOrderId(orders.getId());

        orderVO.setOrderDishes(orderDetailsList.toString());
        orderVO.setOrderDetailList(orderDetailsList);

        return orderVO;
    }

    /**
     * 分页搜索订单
     * @param ordersPageQueryDTO
     * @return
     */
    @Override
    public PageResult pageQuery(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);
        return new PageResult(page.getTotal(), page.getResult());
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Long id = ordersConfirmDTO.getId();
        Orders orders = orderMapper.getById(id);

        if (orders == null) {
            throw new OrderBusinessException("找不到id为" + id + "的订单");
        }

        if (orders.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException("订单不处于待接单状态，无法接单");
        }

        orders.setStatus(Orders.CONFIRMED);
        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param orderRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO orderRejectionDTO) {
        Long id = orderRejectionDTO.getId();
        Orders orders = orderMapper.getById(id);

        if (orders == null) {
            throw new OrderBusinessException("找不到id为" + id + "的订单");
        }

        if (orders.getStatus() != Orders.TO_BE_CONFIRMED) {
            throw new OrderBusinessException("订单不处于待接单状态，无法拒单");
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(orderRejectionDTO.getRejectionReason());
        orderMapper.update(orders);
    }

    /**
     * 接单
     * @param ordersCancelDTO
     */
    @Override
    public void cancel(OrdersCancelDTO ordersCancelDTO) {
        Long id = ordersCancelDTO.getId();
        Orders orders = orderMapper.getById(id);

        if (orders == null) {
            throw new OrderBusinessException("找不到id为" + id + "的订单");
        }

        if (orders.getStatus() != Orders.CONFIRMED) {
            throw new OrderBusinessException("订单不处于已接单状态，无法取消");
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason(ordersCancelDTO.getCancelReason());
        orders.setCancelTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 派送订单
     * @param id
     */
    @Override
    public void delivery(Long id) {
        Orders orders = orderMapper.getById(id);

        if (orders == null) {
            throw new OrderBusinessException("找不到id为" + id + "的订单");
        }

        if (orders.getStatus() != Orders.CONFIRMED) {
            throw new OrderBusinessException("订单不处于已接单状态，无法派送");
        }

        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);
        orders.setDeliveryTime(LocalDateTime.now());
        orderMapper.update(orders);
    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);

        if (orders == null) {
            throw new OrderBusinessException("找不到id为" + id + "的订单");
        }

        if (orders.getStatus() != Orders.DELIVERY_IN_PROGRESS) {
            throw new OrderBusinessException("订单不处于已派送状态，无法完成");
        }

        orders.setStatus(Orders.COMPLETED);
        orderMapper.update(orders);
    }

    /**
     * 查询各个状态的订单数量
     */
    @Override
    public OrderStatisticsVO statistics() {
        OrderStatisticsVO orderStatisticsVO = new OrderStatisticsVO();
        orderStatisticsVO.setConfirmed(orderMapper.count(Orders.CONFIRMED));
        orderStatisticsVO.setDeliveryInProgress(orderMapper.count(Orders.DELIVERY_IN_PROGRESS));
        orderStatisticsVO.setToBeConfirmed(orderMapper.count(Orders.TO_BE_CONFIRMED));

        return orderStatisticsVO;
    }
}
