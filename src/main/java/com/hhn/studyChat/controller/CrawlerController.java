package com.hhn.studyChat.controller;

import com.hhn.studyChat.model.CrawlJob;
import com.hhn.studyChat.service.CrawlerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
public class CrawlerController {

    private final CrawlerService crawlerService;

    @Autowired
    public CrawlerController(CrawlerService crawlerService) {
        this.crawlerService = crawlerService;
    }

    // Startseite mit Formular zum Starten des Crawlers
    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("jobs", crawlerService.getAllJobs());
        return "index";
    }

    // API zum Erstellen eines neuen Jobs
    @PostMapping("/api/jobs")
    public ResponseEntity<CrawlJob> createJob(@RequestParam("url") List<String> urls,
                                              @RequestParam(value = "depth", defaultValue = "1") int depth,
                                              @RequestParam(value = "outputDir", defaultValue = "./collected-content") String outputDir) {
        CrawlJob job = crawlerService.createJob(urls, depth, outputDir);
        return ResponseEntity.ok(job);
    }

    // API zum Starten eines Jobs
    @PostMapping("/api/jobs/{jobId}/start")
    public ResponseEntity<CrawlJob> startJob(@PathVariable String jobId) {
        crawlerService.startJob(jobId);
        return ResponseEntity.ok(crawlerService.getJob(jobId));
    }

    // API zum Abrufen des Job-Status
    @GetMapping("/api/jobs/{jobId}")
    public ResponseEntity<CrawlJob> getJob(@PathVariable String jobId) {
        CrawlJob job = crawlerService.getJob(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(job);
    }

    // API zum Abrufen aller Jobs
    @GetMapping("/api/jobs")
    public ResponseEntity<List<CrawlJob>> getAllJobs() {
        return ResponseEntity.ok(crawlerService.getAllJobs());
    }
}