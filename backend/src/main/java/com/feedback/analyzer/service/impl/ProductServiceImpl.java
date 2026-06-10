package com.feedback.analyzer.service.impl;

import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.repository.ProductRepository;
import com.feedback.analyzer.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepo;

    @Override
    public Product create(Product product) {
        if (productRepo.existsByName(product.getName())) {
            throw new RuntimeException("产品名称已存在: " + product.getName());
        }
        if (product.getFeishuEnabled() == null) {
            product.setFeishuEnabled(false);
        }
        return productRepo.save(product);
    }

    @Override
    public Product update(Long id, Product product) {
        Product existing = productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("产品不存在: " + id));
        existing.setName(product.getName());
        existing.setDescription(product.getDescription());
        existing.setTeamName(product.getTeamName());
        existing.setEnabled(product.getEnabled());
        existing.setZentaoProductId(product.getZentaoProductId());
        existing.setFeishuEnabled(Boolean.TRUE.equals(product.getFeishuEnabled()));
        if (Boolean.TRUE.equals(product.getClearFeishuWebhook())) {
            existing.setFeishuWebhookUrl(null);
        } else if (product.getFeishuWebhookUrl() != null && !product.getFeishuWebhookUrl().isBlank()) {
            existing.setFeishuWebhookUrl(product.getFeishuWebhookUrl().trim());
        }
        return productRepo.save(existing);
    }

    @Override
    public void delete(Long id) {
        productRepo.deleteById(id);
    }

    @Override
    public Product getById(Long id) {
        return productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("产品不存在: " + id));
    }

    @Override
    public Product getByWebhookToken(String token) {
        Product product = productRepo.findByWebhookToken(token)
                .orElseThrow(() -> new RuntimeException("Webhook Token 无效"));
        if (!Boolean.TRUE.equals(product.getEnabled())) {
            throw new RuntimeException("当前产品已停用，无法接收反馈");
        }
        return product;
    }

    @Override
    public List<Product> list() {
        return productRepo.findAll();
    }

    @Override
    public void toggleEnabled(Long id, boolean enabled) {
        Product product = getById(id);
        product.setEnabled(enabled);
        productRepo.save(product);
    }

    @Override
    public String regenerateToken(Long id) {
        Product product = getById(id);
        String newToken = UUID.randomUUID().toString().replace("-", "");
        product.setWebhookToken(newToken);
        productRepo.save(product);
        return newToken;
    }
}
