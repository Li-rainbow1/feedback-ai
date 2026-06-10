package com.feedback.analyzer.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${redisson.address}")
    private String address;

    @Value("${redisson.password}")
    private String password;

    @Value("${redisson.database:0}")
    private int database;

    @Value("${redisson.timeout:3000}")
    private int timeout;

    @Value("${redisson.connection-pool-size:32}")
    private int poolSize;

    @Value("${redisson.connection-minimum-idle-size:8}")
    private int minIdleSize;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(address)
                .setPassword(password.isBlank() ? null : password)
                .setDatabase(database)
                .setTimeout(timeout)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(minIdleSize);
        return Redisson.create(config);
    }
}
