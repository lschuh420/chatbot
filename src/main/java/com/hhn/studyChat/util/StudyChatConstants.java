package com.hhn.studyChat.util;

/**
 * Konstanten für das StudyChat Crawler-System
 */
public class StudyChatConstants {

    // Metadata-Schlüssel für die Crawler-Tiefe
    public static final String DEPTH_KEY = "depth";
    public static final String PARENT_URL_KEY = "parent.url";
    public static final String CURRENT_DEPTH_KEY = "depth.current";
    public static final String NEXT_DEPTH_KEY = "depth.next";
    public static final String MAX_DEPTH_KEY = "depth.max";

    // Konfigurationsschlüssel
    public static final String MAX_DEPTH_CONFIG_KEY = "max.depth";
    public static final String OUTPUT_DIR_CONFIG_KEY = "output.dir";
    public static final String CRAWLER_ID_CONFIG_KEY = "crawler.id";

    // Status-Metadata
    public static final String REDIRECT_SOURCE_KEY = "redirect.source";
    public static final String STATUS_KEY = "status";
    public static final String RETRY_COUNT_KEY = "retry.count";

    // URL-Filter Kategorien
    public static final String CATEGORY_STUDIUM = "studium";
    public static final String CATEGORY_FORSCHUNG = "forschung";
    public static final String CATEGORY_INTERNATIONAL = "international";
    public static final String CATEGORY_NEWS = "news";
    public static final String CATEGORY_EVENTS = "events";
    public static final String CATEGORY_KONTAKT = "kontakt";
    public static final String CATEGORY_ALLGEMEIN = "allgemein";

    // URL-Pattern für HHN-Website
    public static final String HHN_STUDIUM_PATTERN = "/studium/";
    public static final String HHN_FORSCHUNG_PATTERN = "/forschung/";
    public static final String HHN_NEWS_PATTERN = "/news/";
    public static final String HHN_AKTUELLES_PATTERN = "/aktuelles/";
    public static final String HHN_EVENTS_PATTERN = "/events/";
    public static final String HHN_VERANSTALTUNGEN_PATTERN = "/veranstaltungen/";
    public static final String HHN_KONTAKT_PATTERN = "/kontakt/";
    public static final String HHN_INTERNATIONAL_PATTERN = "/international/";

    // Standard-Werte
    public static final int DEFAULT_MAX_DEPTH = 5;
    public static final int DEFAULT_MAX_RETRIES = 3;
    public static final String DEFAULT_OUTPUT_DIR = "./collected-content";
    public static final String DEFAULT_INDEX_FILE = "crawl_index.json";

    // User-Agent Informationen
    public static final String USER_AGENT_NAME = "StudyChat-Bot";
    public static final String USER_AGENT_VERSION = "1.0";
    public static final String USER_AGENT_DESCRIPTION = "StudyChat Web Crawler für Hochschule Heilbronn";
    public static final String USER_AGENT_URL = "https://www.hs-heilbronn.de";
    public static final String USER_AGENT_EMAIL = "contact@example.com";

    // Statistik-Intervalle
    public static final int STATS_LOG_INTERVAL = 50;
    public static final int DETAILED_STATS_LOG_INTERVAL = 100;

    private StudyChatConstants() {
        // Utility-Klasse, keine Instanziierung
    }
}