package com.feedback.analyzer.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTest {

    @Test
    void exposesFeishuTriState() {
        Product disabled = Product.builder()
                .feishuEnabled(false)
                .build();
        Product missingWebhook = Product.builder()
                .feishuEnabled(true)
                .feishuWebhookUrl("")
                .build();
        Product enabled = Product.builder()
                .feishuEnabled(true)
                .feishuWebhookUrl("https://open.feishu.cn/robot")
                .build();

        assertThat(disabled.getFeishuStatus()).isEqualTo("DISABLED");
        assertThat(disabled.getFeishuStatusLabel()).isEqualTo("已关闭");

        assertThat(missingWebhook.getFeishuStatus()).isEqualTo("MISSING_WEBHOOK");
        assertThat(missingWebhook.getFeishuStatusLabel()).isEqualTo("未配置地址");

        assertThat(enabled.getFeishuStatus()).isEqualTo("ENABLED");
        assertThat(enabled.getFeishuStatusLabel()).isEqualTo("已启用");
    }
}
