package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByName(String name);

    Optional<Product> findByWebhookToken(String webhookToken);

    boolean existsByName(String name);
}
