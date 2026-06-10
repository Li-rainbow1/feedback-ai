package com.feedback.analyzer.repository;

import com.feedback.analyzer.entity.IssueTimeline;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IssueTimelineRepository extends JpaRepository<IssueTimeline, Long> {

    List<IssueTimeline> findByIssueIdOrderByCreatedAtDesc(String issueId);
}
