package org.example.crawler;

import org.example.crawler.service.CrawlerRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CrawlerApplication implements CommandLineRunner {
    @Autowired
    private CrawlerRunner crawlerRunner;

    public static void main(String[] args) {
        SpringApplication.run(CrawlerApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        crawlerRunner.startTopology();
    }
}