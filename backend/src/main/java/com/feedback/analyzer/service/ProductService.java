package com.feedback.analyzer.service;

import com.feedback.analyzer.entity.Product;
import java.util.List;

public interface ProductService {

    Product create(Product product);

    Product update(Long id, Product product);

    void delete(Long id);

    Product getById(Long id);

    Product getByWebhookToken(String token);

    List<Product> list();

    void toggleEnabled(Long id, boolean enabled);

    String regenerateToken(Long id);
}
