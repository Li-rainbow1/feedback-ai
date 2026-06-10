package com.feedback.analyzer.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiClaimResult {

    @JsonProperty("category")
    private String category;

    @JsonProperty("module")
    private String module;

    @JsonProperty("keywords")
    private List<String> keywords;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("content")
    private String content;

    @JsonProperty("isPrimary")
    private Boolean isPrimary;
}
