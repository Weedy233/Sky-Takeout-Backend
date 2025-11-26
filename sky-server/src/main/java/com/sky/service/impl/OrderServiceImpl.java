package com.sky.service.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
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
import com.sky.utils.HttpClientUtil;
import com.sky.utils.WeChatPayUtil;
import com.sky.vo.OrderPaymentVO;
import com.sky.vo.OrderStatisticsVO;
import com.sky.vo.OrderSubmitVO;
import com.sky.vo.OrderVO;
import com.sky.websocket.WebSocketServer;

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
    @Autowired
    private WebSocketServer webSocketServer;

    @Value("${sky.shop.address}")
    private String shopAddress;

    @Value("${sky.baidu.ak}")
    private String ak;

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

        // 检查用户的收货地址是否超出配送范围
        // Note: 因为没有配置实际的百度应用密钥，所以将该行注释掉
        // checkOutOfRange(addressBook.getCityName() + addressBook.getDistrictName() + addressBook.getDetail());

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

        // 通过 WebSocket 向商家客户端发送来单消息 
        Map<String, Object> map = new HashMap<>();
        map.put("type", 1);
        map.put("orderId", ordersDB.getId());
        map.put("content", "订单号：" + outTradeNo);

        String jsonString = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(jsonString);
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
    public PageResult conditionalSearch(OrdersPageQueryDTO ordersPageQueryDTO) {
        PageHelper.startPage(ordersPageQueryDTO.getPage(), ordersPageQueryDTO.getPageSize());
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> orderVOList = getOrderVOList(page);

        return new PageResult(page.getTotal(), orderVOList);
    }

    private List<OrderVO> getOrderVOList(Page<Orders> page) {
        List<OrderVO> orderVOList = new ArrayList<>();

        List<Orders> ordersList = page.getResult();
        if (!ordersList.isEmpty()) {
            for (Orders orders: ordersList) {
                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);

                String orderDishesString = getOrderDishesString(orders);

                orderVO.setOrderDishes(orderDishesString);
                orderVOList.add(orderVO);
            }
        }

        return orderVOList;
    }

    /**
     * 根据订单id获取菜品信息字符串
     *
     * @param orders
     * @return
     */
    private String getOrderDishesString(Orders orders) {
        // 查询订单菜品详情信息（订单中的菜品和数量）
        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(orders.getId());

        // 将每一条订单菜品信息拼接为字符串（格式：宫保鸡丁*3；）
        List<String> orderDishList = orderDetailList.stream().map(x -> {
            String orderDish = x.getName() + "*" + x.getNumber() + ";";
            return orderDish;
        }).collect(Collectors.toList());

        // 将该订单对应的所有菜品信息拼接在一起
        return String.join("", orderDishList);
    }

    /**
     * 接单
     * @param ordersConfirmDTO
     */
    @Override
    public void confirm(OrdersConfirmDTO ordersConfirmDTO) {
        Long id = ordersConfirmDTO.getId();
        Orders orders = orderMapper.getById(id);

        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        orders.setStatus(Orders.CONFIRMED);

        orderMapper.update(orders);
    }

    /**
     * 拒单
     * @param orderRejectionDTO
     */
    @Override
    public void rejection(OrdersRejectionDTO orderRejectionDTO) throws Exception {
        Orders orders = orderMapper.getById(orderRejectionDTO.getId());

        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if (orders.getUserId() != BaseContext.getCurrentId()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NO_PERMISSIONS);
        }

        Integer payStatus = orders.getPayStatus();
        if (payStatus == Orders.PAID) {
            // String refund = weChatPayUtil.refund(
            //     orders.getNumber(),
            //     orders.getNumber(),
            //     new BigDecimal(0.01), 
            //     new BigDecimal(0.01)
            // );
            // log.info("申请退款：{}", refund);
            log.info("申请退款：{}");
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setRejectionReason(orderRejectionDTO.getRejectionReason());
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    /**
     * 商家取消订单
     * @param ordersCancelDTO
     */
    @Override
    public void adminCancel(OrdersCancelDTO ordersCancelDTO) throws Exception {
        Orders orders = orderMapper.getById(ordersCancelDTO.getId());

        // 支付状态
        Integer payStatus = orders.getPayStatus();
        if (payStatus == Orders.PAID) {
            // 用户已支付，需要退款
            // String refund = weChatPayUtil.refund(
            //         orders.getNumber(),
            //         orders.getNumber(),
            //         new BigDecimal(0.01),
            //         new BigDecimal(0.01));
            // log.info("申请退款：{}", refund);
            log.info("申请退款：{}");
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

        if (orders == null || !orders.getStatus().equals(Orders.CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if (orders.getUserId() != BaseContext.getCurrentId()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NO_PERMISSIONS);
        }

        orders.setStatus(Orders.DELIVERY_IN_PROGRESS);

        orderMapper.update(orders);
    }

    /**
     * 完成订单
     * @param id
     */
    @Override
    public void complete(Long id) {
        Orders orders = orderMapper.getById(id);

        if (orders == null || !orders.getStatus().equals(Orders.DELIVERY_IN_PROGRESS)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        orders.setStatus(Orders.COMPLETED);
        orders.setDeliveryTime(LocalDateTime.now());

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

    /**
     * 用户取消订单
     */
    @Override
    public void userCancel(Long id) throws Exception {
        Orders ordersDB = orderMapper.getById(id);

        if (ordersDB == null) {
            throw new OrderBusinessException(MessageConstant.ORDER_NOT_FOUND);
        }

        if (ordersDB.getStatus() > 2) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }

        if (ordersDB.getUserId() != BaseContext.getCurrentId()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NO_PERMISSIONS);
        }

        Orders orders = new Orders();
        orders.setId(ordersDB.getId());

        if (ordersDB.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            // weChatPayUtil.refund(
            //         ordersDB.getNumber(), // 商户订单号
            //         ordersDB.getNumber(), // 商户退款单号
            //         new BigDecimal(0.01), // 退款金额，单位 元
            //         new BigDecimal(0.01)
            // );// 原订单金额

            log.info("用户退款：{}");
            // 支付状态修改为 退款
            orders.setPayStatus(Orders.REFUND);
        }

        orders.setStatus(Orders.CANCELLED);
        orders.setCancelReason("用户取消");
        orders.setCancelTime(LocalDateTime.now());

        orderMapper.update(orders);
    }

    @SuppressWarnings("null")
    @Override
/**
     * 用户端订单分页查询
     *
     * @param pageNum
     * @param pageSize
     * @param status
     * @return
     */
    public PageResult pageQuery4User(int pageNum, int pageSize, Integer status) {
        // 设置分页
        PageHelper.startPage(pageNum, pageSize);

        OrdersPageQueryDTO ordersPageQueryDTO = new OrdersPageQueryDTO();
        ordersPageQueryDTO.setUserId(BaseContext.getCurrentId());
        ordersPageQueryDTO.setStatus(status);

        // 分页条件查询
        Page<Orders> page = orderMapper.pageQuery(ordersPageQueryDTO);

        List<OrderVO> list = new ArrayList<>();

        // 查询出订单明细，并封装入OrderVO进行响应
        if (page != null && page.getTotal() > 0) {
            for (Orders orders : page) {
                Long orderId = orders.getId();// 订单id

                // 查询订单明细
                List<OrderDetail> orderDetails = orderDetailMapper.getByOrderId(orderId);

                OrderVO orderVO = new OrderVO();
                BeanUtils.copyProperties(orders, orderVO);
                orderVO.setOrderDetailList(orderDetails);

                list.add(orderVO);
            }
        }
        return new PageResult(page.getTotal(), list);
    }

    /**
     * 用户再来一单
     */
    @Override
    public void repetition(Long id) {
        Long userId = BaseContext.getCurrentId();

        List<OrderDetail> orderDetailList = orderDetailMapper.getByOrderId(id);

        List<ShoppingCart> shoppingCartList =
        orderDetailList.stream().map(x -> {
            ShoppingCart shoppingCart = new ShoppingCart();

            BeanUtils.copyProperties(x, shoppingCart, "id");
            shoppingCart.setUserId(userId);
            shoppingCart.setCreateTime(LocalDateTime.now());

            return shoppingCart;
        }).collect(Collectors.toList());

        shoppingCartMapper.insertBatch(shoppingCartList);
    }


    /**
     * 检查客户的收货地址是否超出配送范围
     * 
     * @param address
     */
    @SuppressWarnings("unused")
    private void checkOutOfRange(String address) {
        Map<String, String> map = new HashMap<String, String>();
        map.put("address", shopAddress);
        map.put("output", "json");
        map.put("ak", ak);

        // 获取店铺的经纬度坐标
        String shopCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        JSONObject jsonObject = JSON.parseObject(shopCoordinate);
        if (!jsonObject.getString("status").equals("0")) {
            throw new OrderBusinessException("店铺地址解析失败");
        }

        // 数据解析
        JSONObject location = jsonObject.getJSONObject("result").getJSONObject("location");
        String lat = location.getString("lat");
        String lng = location.getString("lng");
        // 店铺经纬度坐标
        String shopLngLat = lat + "," + lng;

        map.put("address", address);
        // 获取用户收货地址的经纬度坐标
        String userCoordinate = HttpClientUtil.doGet("https://api.map.baidu.com/geocoding/v3", map);

        jsonObject = JSON.parseObject(userCoordinate);
        if (!jsonObject.getString("status").equals("0")) {
            throw new OrderBusinessException("收货地址解析失败");
        }

        // 数据解析
        location = jsonObject.getJSONObject("result").getJSONObject("location");
        lat = location.getString("lat");
        lng = location.getString("lng");
        // 用户收货地址经纬度坐标
        String userLngLat = lat + "," + lng;

        map.put("origin", shopLngLat);
        map.put("destination", userLngLat);
        map.put("steps_info", "0");

        // 路线规划
        String json = HttpClientUtil.doGet("https://api.map.baidu.com/directionlite/v1/driving", map);

        jsonObject = JSON.parseObject(json);
        if (!jsonObject.getString("status").equals("0")) {
            throw new OrderBusinessException("配送路线规划失败");
        }

        // 数据解析
        JSONObject result = jsonObject.getJSONObject("result");
        JSONArray jsonArray = (JSONArray) result.get("routes");
        Integer distance = (Integer) ((JSONObject) jsonArray.get(0)).get("distance");

        if (distance > 5000) {
            // 配送距离超过5000米
            throw new OrderBusinessException("超出配送范围");
        }
    }

    /**
     * 用户催单
     * @param orderId 订单号
     */
    @Override
    public void reminder(Long orderId) {
        Orders orders = orderMapper.getById(orderId);
        if (orders == null || !orders.getStatus().equals(Orders.TO_BE_CONFIRMED)) {
            throw new OrderBusinessException(MessageConstant.ORDER_STATUS_ERROR);
        }
        if (orders.getUserId() != BaseContext.getCurrentId()) {
            throw new OrderBusinessException(MessageConstant.ORDER_NO_PERMISSIONS);
        }

        Map<String, Object> map = new HashMap<>();
        map.put("type", 2);
        map.put("orderId", orders.getId());
        map.put("content", "订单号：" + orders.getNumber());

        String jsonString = JSON.toJSONString(map);
        webSocketServer.sendToAllClient(jsonString);
    }
}
