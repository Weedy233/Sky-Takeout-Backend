package com.sky.controller.notify;

import com.alibaba.druid.support.json.JSONUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sky.properties.WeChatProperties;
import com.sky.service.OrderService;
import com.wechat.pay.contrib.apache.httpclient.util.AesUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

/**
 * 支付回调相关接口
 */
@RestController
@RequestMapping("/notify")
@Slf4j
public class PayNotifyController {
    @Autowired
    private OrderService orderService;
    @Autowired
    private WeChatProperties weChatProperties;

    // --- mark: 以下内容为 AI 添加 ---
    /**
     * 模拟支付成功回调（用于测试）
     *
     * @param outTradeNo 商户订单号
     * @return
     */
    @GetMapping("/mockPaySuccess")
    public HashMap<String, Object> mockPaySuccess(@RequestParam String outTradeNo) {
        log.info("=== 模拟支付成功回调 ===");
        log.info("商户平台订单号：{}", outTradeNo);
        
        // 检查是否为模拟模式
        if (!isMockMode()) {
            HashMap<String, Object> error = new HashMap<>();
            error.put("code", "ERROR");
            error.put("message", "当前不是模拟模式，无法使用模拟支付");
            return error;
        }
        
        try {
            // 生成模拟的微信支付交易号
            String mockTransactionId = "MOCK_TX_" + System.currentTimeMillis();
            
            // 业务处理，修改订单状态、来单提醒
            orderService.paySuccess(outTradeNo);
            
            log.info("模拟微信支付交易号：{}", mockTransactionId);
            log.info("=== 模拟支付回调完成 ===");
            
            HashMap<String, Object> result = new HashMap<>();
            result.put("code", "SUCCESS");
            result.put("message", "模拟支付成功");
            result.put("out_trade_no", outTradeNo);
            result.put("transaction_id", mockTransactionId);
            
            return result;
        } catch (Exception e) {
            log.error("模拟支付回调失败", e);
            HashMap<String, Object> error = new HashMap<>();
            error.put("code", "ERROR");
            error.put("message", "模拟支付失败：" + e.getMessage());
            return error;
        }
    }

    /**
     * 检查是否为模拟模式
     * @return
     */
    private boolean isMockMode() {
        return "***".equals(weChatProperties.getMchid()) || 
               weChatProperties.getMchid() == null || 
               weChatProperties.getMchid().trim().isEmpty() ||
               "***".equals(weChatProperties.getPrivateKeyFilePath()) ||
               weChatProperties.getPrivateKeyFilePath() == null ||
               "***".equals(weChatProperties.getApiV3Key());
    }
    // --- mark: 以上内容为 AI 添加 ---

    /**
     * 支付成功回调
     *
     * @param request
     */
    @RequestMapping("/paySuccess")
    public void paySuccessNotify(HttpServletRequest request, HttpServletResponse response) throws Exception {
        // 读取数据
        String body = readData(request);
        log.info("支付成功回调：{}", body);

        // 数据解密
        String plainText = decryptData(body);
        log.info("解密后的文本：{}", plainText);

        JSONObject jsonObject = JSON.parseObject(plainText);
        String outTradeNo = jsonObject.getString("out_trade_no");// 商户平台订单号
        String transactionId = jsonObject.getString("transaction_id");// 微信支付交易号

        log.info("商户平台订单号：{}", outTradeNo);
        log.info("微信支付交易号：{}", transactionId);

        // 业务处理，修改订单状态、来单提醒
        orderService.paySuccess(outTradeNo);

        // 给微信响应
        responseToWeixin(response);
    }

    /**
     * 读取数据
     *
     * @param request
     * @return
     * @throws Exception
     */
    private String readData(HttpServletRequest request) throws Exception {
        BufferedReader reader = request.getReader();
        StringBuilder result = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            if (result.length() > 0) {
                result.append("\n");
            }
            result.append(line);
        }
        return result.toString();
    }

    /**
     * 数据解密
     *
     * @param body
     * @return
     * @throws Exception
     */
    private String decryptData(String body) throws Exception {
        JSONObject resultObject = JSON.parseObject(body);
        JSONObject resource = resultObject.getJSONObject("resource");
        String ciphertext = resource.getString("ciphertext");
        String nonce = resource.getString("nonce");
        String associatedData = resource.getString("associated_data");

        AesUtil aesUtil = new AesUtil(weChatProperties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
        // 密文解密
        String plainText = aesUtil.decryptToString(associatedData.getBytes(StandardCharsets.UTF_8),
                nonce.getBytes(StandardCharsets.UTF_8),
                ciphertext);

        return plainText;
    }

    /**
     * 给微信响应
     * 
     * @param response
     */
    private void responseToWeixin(HttpServletResponse response) throws Exception {
        response.setStatus(200);
        HashMap<Object, Object> map = new HashMap<>();
        map.put("code", "SUCCESS");
        map.put("message", "SUCCESS");
        response.setHeader("Content-type", ContentType.APPLICATION_JSON.toString());
        response.getOutputStream().write(JSONUtils.toJSONString(map).getBytes(StandardCharsets.UTF_8));
        response.flushBuffer();
    }
}
