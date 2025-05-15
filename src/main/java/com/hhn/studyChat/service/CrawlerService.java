package com.hhn.studyChat.service;

import com.hhn.studyChat.model.CrawlJob;
import com.hhn.studyChat.util.TopologyRunner;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class CrawlerService {

    private final Map<String, CrawlJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    // Erstelle einen neuen Crawling-Job
    public CrawlJob createJob(List<String> seedUrls, int maxDepth, String outputDir) {
        CrawlJob job = CrawlJob.create(seedUrls, maxDepth, outputDir);
        jobs.put(job.getId(), job);
        return job;
    }

    // Starte einen existierenden Job
    public void startJob(String jobId) {
        CrawlJob job = jobs.get(jobId);
        if (job == null || !"QUEUED".equals(job.getStatus())) {
            throw new IllegalStateException("Job nicht gefunden oder nicht in der Queue");
        }

        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());

        executorService.submit(() -> {
            try {
                // Topologie ausführen
                TopologyRunner.runTopology(
                        job.getSeedUrls().toArray(new String[0]),
                        job.getMaxDepth(),
                        job.getOutputDirectory(),
                        job.getId()
                );

                // Nach erfolgreichem Abschluss
                job.setStatus("COMPLETED");
                job.setCompletedAt(LocalDateTime.now());
            } catch (Exception e) {
                job.setStatus("FAILED");
                job.setCompletedAt(LocalDateTime.now());
                // Log-Exception
                e.printStackTrace();
            }
        });
    }

    // Hole Job nach ID
    public CrawlJob getJob(String jobId) {
        return jobs.get(jobId);
    }

    // Liste alle Jobs
    public List<CrawlJob> getAllJobs() {
        return new ArrayList<>(jobs.values());
    }

    // Aktualisiere Job-Statistiken
    public void updateJobStats(String jobId, int crawledUrlsCount) {
        CrawlJob job = jobs.get(jobId);
        if (job != null) {
            job.setCrawledUrlsCount(crawledUrlsCount);
        }
    }
}