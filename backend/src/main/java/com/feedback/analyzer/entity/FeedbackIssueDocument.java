package com.feedback.analyzer.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;

// Spring Data 写入 ES 时会带 _class 字段，KNN 查询用 Java Client 反序列化时需要忽略它。
@JsonIgnoreProperties(ignoreUnknown = true)
@Document(indexName = "feedback_issue", createIndex = false)
@Setting(settingPath = "es/feedback-issue-setting.json")
public class FeedbackIssueDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String issueId;

    @Field(type = FieldType.Text)
    private String title;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Keyword)
    private String severity;

    @Field(type = FieldType.Long)
    private Long productId;

    @Field(type = FieldType.Date)
    private String createdAt;

    @Field(type = FieldType.Integer)
    private Integer reportCount;

    @Field(type = FieldType.Dense_Vector, dims = 1024)
    private double[] embedding;

    public FeedbackIssueDocument() {}

    public FeedbackIssueDocument(String issueId, String title, String category,
                                  String severity, Long productId, LocalDateTime createdAt, Integer reportCount,
                                  double[] embedding) {
        this.issueId = issueId;
        this.title = title;
        this.category = category;
        this.severity = severity;
        this.productId = productId;
        this.createdAt = createdAt != null ? createdAt.toString() : null;
        this.reportCount = reportCount;
        this.embedding = embedding;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getIssueId() { return issueId; }
    public void setIssueId(String issueId) { this.issueId = issueId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public Integer getReportCount() { return reportCount; }
    public void setReportCount(Integer reportCount) { this.reportCount = reportCount; }
    public double[] getEmbedding() { return embedding; }
    public void setEmbedding(double[] embedding) { this.embedding = embedding; }
}
