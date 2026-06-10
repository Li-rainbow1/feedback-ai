package com.feedback.analyzer.service;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromptTemplateService {

    private static final String PROMPT_DIR = "classpath:prompts/";

    private final ResourceLoader resourceLoader;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String load(String name) {
        return cache.computeIfAbsent(name, this::readPrompt);
    }

    public String render(String name, Map<String, ?> variables) {
        String prompt = load(name);
        if (variables == null || variables.isEmpty()) {
            return prompt;
        }
        String result = prompt;
        for (Map.Entry<String, ?> entry : variables.entrySet()) {
            String value = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }

    private String readPrompt(String name) {
        Resource resource = resourceLoader.getResource(PROMPT_DIR + name);
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt template: " + name, e);
        }
    }
}
