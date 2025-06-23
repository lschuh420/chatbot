package com.hhn.studyChat.model;

import lombok.Data;
import lombok.Builder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class CrawlJob {
    private String id;
    private List<String> seedUrls;
    private int maxDepth;
    private boolean sitemapCrawl;
    private String status; // QUEUED, RUNNING, COMPLETED, FAILED
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String outputDirectory;
    private int crawledUrlsCount;

    public static CrawlJob create(List<String> seedUrls, int maxDepth, String outputDir, boolean sitemapCrawl) {
        return CrawlJob.builder()
                .id(UUID.randomUUID().toString())
                .seedUrls(seedUrls)
                .maxDepth(maxDepth)
                .sitemapCrawl(sitemapCrawl)
                .status("QUEUED")
                .createdAt(LocalDateTime.now())
                .outputDirectory(outputDir)
                .crawledUrlsCount(0)
                .build();
    }
}