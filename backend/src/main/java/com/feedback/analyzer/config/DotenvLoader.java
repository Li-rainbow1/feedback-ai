package com.feedback.analyzer.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DotenvLoader implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
        List<String> candidates = List.of(".env", "docs/.env", "../docs/.env");
        Map<String, Object> merged = new HashMap<>();
        for (String candidate : candidates) {
            Path path = Paths.get(candidate);
            if (Files.exists(path)) {
                try {
                    for (String line : Files.readAllLines(path)) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        int eq = line.indexOf('=');
                        if (eq > 0) {
                            String key = line.substring(0, eq).trim();
                            String value = line.substring(eq + 1).trim();
                            if (!value.isEmpty()) {
                                merged.put(key, value);
                            }
                        }
                    }
                } catch (IOException ignored) {}
            }
        }
        if (!merged.isEmpty()) {
            if (merged.containsKey("EMBEDDING_API_KEY")) {
                merged.putIfAbsent("embedding.api-key", merged.get("EMBEDDING_API_KEY"));
            }
            env.getPropertySources().addFirst(new MapPropertySource("dotenv", merged));
        }
    }
}
