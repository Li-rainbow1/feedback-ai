package com.feedback.analyzer.controller;

import com.feedback.analyzer.entity.Product;
import com.feedback.analyzer.model.ApiResult;
import com.feedback.analyzer.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResult<List<Product>> list() {
        return ApiResult.success(productService.list());
    }

    @GetMapping("/{id}")
    public ApiResult<Product> getById(@PathVariable Long id) {
        return ApiResult.success(productService.getById(id));
    }

    @PostMapping
    public ApiResult<Product> create(@Valid @RequestBody Product product) {
        return ApiResult.success(productService.create(product));
    }

    @PutMapping("/{id}")
    public ApiResult<Product> update(@PathVariable Long id, @Valid @RequestBody Product product) {
        return ApiResult.success(productService.update(id, product));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResult.success();
    }

    @PatchMapping("/{id}/toggle")
    public ApiResult<Void> toggleEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        productService.toggleEnabled(id, body.get("enabled"));
        return ApiResult.success();
    }

    @PostMapping("/{id}/regenerate-token")
    public ApiResult<Map<String, String>> regenerateToken(@PathVariable Long id) {
        return ApiResult.success(Map.of("token", productService.regenerateToken(id)));
    }
}
