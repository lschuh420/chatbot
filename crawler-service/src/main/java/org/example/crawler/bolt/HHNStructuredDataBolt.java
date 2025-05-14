package org.example.crawler.bolt;

import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.base.BaseRichBolt;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.storm.tuple.Fields;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.digitalpebble.stormcrawler.Metadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Optimiert für die Hochschule Heilbronn-Website
 * Extrahiert strukturierte Daten und bereitet sie für die RAG-Verarbeitung vor
 */
public class HHNStructuredDataBolt extends BaseRichBolt {

    private OutputCollector collector;

    @Override
    @SuppressWarnings("rawtypes")
    public void prepare(Map stormConf, TopologyContext context, OutputCollector coll) {
        this.collector = coll;
    }

    @Override
    public void execute(Tuple tuple) {
        try {
            String url = tuple.getStringByField("url");
            byte[] content = tuple.getBinaryByField("content");
            String html = new String(content, StandardCharsets.UTF_8);

            // Metadata extrahieren oder erstellen
            Metadata metadata;
            try {
                metadata = (Metadata) tuple.getValueByField("metadata");
            } catch (Exception e) {
                metadata = new Metadata();
            }

            // JSoup-Parsen des HTML
            Document doc = Jsoup.parse(html, url);

            // 1. Events extrahieren - bei HS Heilbronn sind diese oft in Event-Karten
            Elements eventEls = doc.select(".event, .veranstaltung, [data-eventdate], .event-teaser, .calendar-item");
            List<Map<String, String>> events = new ArrayList<>();

            for (Element el : eventEls) {
                Map<String, String> event = new HashMap<>();

                // Titel
                String title = "";
                Element titleEl = el.selectFirst("h3, h4, .event-title, .title");
                if (titleEl != null) {
                    title = titleEl.text().trim();
                }

                // Datum
                String date = "";
                Element dateEl = el.selectFirst(".date, .event-date, [data-eventdate], time");
                if (dateEl != null) {
                    date = dateEl.text().trim();
                } else if (el.hasAttr("data-eventdate")) {
                    date = el.attr("data-eventdate");
                }

                // Beschreibung
                String desc = "";
                Element descEl = el.selectFirst(".description, .event-description, p");
                if (descEl != null) {
                    desc = descEl.text().trim();
                }

                // Ort
                String location = "";
                Element locEl = el.selectFirst(".location, .event-location, .place");
                if (locEl != null) {
                    location = locEl.text().trim();
                }

                if (!title.isEmpty()) {
                    event.put("title", title);
                    event.put("date", date);
                    event.put("description", desc);
                    event.put("location", location);
                    events.add(event);
                }
            }

            // 2. News-Teaser extrahieren
            Elements newsEls = doc.select(".news, .news-item, .news-teaser, article, .aktuelles-item");
            List<Map<String, String>> news = new ArrayList<>();

            for (Element el : newsEls) {
                Map<String, String> newsItem = new HashMap<>();

                // Titel
                String title = "";
                Element titleEl = el.selectFirst("h3, h4, .news-title, .title");
                if (titleEl != null) {
                    title = titleEl.text().trim();
                }

                // Datum
                String date = "";
                Element dateEl = el.selectFirst(".date, .news-date, time");
                if (dateEl != null) {
                    date = dateEl.text().trim();
                }

                // Beschreibung/Teaser
                String desc = "";
                Element descEl = el.selectFirst(".description, .news-description, .teaser, p");
                if (descEl != null) {
                    desc = descEl.text().trim();
                }

                // Link
                String link = "";
                Element linkEl = el.selectFirst("a");
                if (linkEl != null && linkEl.hasAttr("href")) {
                    link = linkEl.attr("abs:href");
                }

                if (!title.isEmpty()) {
                    newsItem.put("title", title);
                    newsItem.put("date", date);
                    newsItem.put("description", desc);
                    newsItem.put("link", link);
                    news.add(newsItem);
                }
            }

            // 3. Studiengänge extrahieren
            Elements courseEls = doc.select(".studiengang, .course, .degree-program, li[data-course]");
            List<String> courses = new ArrayList<>();

            for (Element el : courseEls) {
                String course = el.text().trim();
                if (!course.isEmpty()) {
                    courses.add(course);
                }
            }

            // 4. Metadaten erweitern
            extractMetadata(doc, metadata);

            // 5. Haupttext strukturiert extrahieren
            String fullText = extractStructuredText(doc);

            // Ausgabe
            System.out.println("Extracted " + events.size() + " events, " + news.size() + " news items, and " + courses.size() + " courses from " + url);

            // Emit eines neuen, strukturierten Tuples
            collector.emit(tuple, new Values(url, events, news, courses, fullText, metadata));
            collector.ack(tuple);

        } catch (Exception e) {
            System.err.println("Error in HHNStructuredDataBolt: " + e.getMessage());
            e.printStackTrace();
            collector.fail(tuple);
        }
    }

    /**
     * Extrahiert strukturierten Text aus dem Dokument
     */
    private String extractStructuredText(Document doc) {
        StringBuilder structuredText = new StringBuilder();

        // Titel der Seite
        Element title = doc.selectFirst("title");
        if (title != null) {
            structuredText.append("TITLE: ").append(title.text()).append("\n\n");
        }

        // Hauptinhalt - Überschriften und Absätze
        Elements contentElements = doc.select("h1, h2, h3, h4, p, .content p, article p, .main-content p, [role=main] p");

        String currentHeading = "";
        for (Element el : contentElements) {
            String tagName = el.tagName().toLowerCase();

            if (tagName.startsWith("h")) {
                // Überschrift
                currentHeading = el.text().trim();
                structuredText.append("SECTION: ").append(currentHeading).append("\n");
            } else if (tagName.equals("p")) {
                // Absatz
                String paragraphText = el.text().trim();
                if (!paragraphText.isEmpty()) {
                    if (!currentHeading.isEmpty()) {
                        structuredText.append(paragraphText).append("\n\n");
                    } else {
                        structuredText.append(paragraphText).append("\n\n");
                    }
                }
            }
        }

        // Listen
        Elements lists = doc.select("ul, ol");
        for (Element list : lists) {
            Elements items = list.select("li");
            if (!items.isEmpty()) {
                structuredText.append("LIST:\n");
                for (Element item : items) {
                    String itemText = item.text().trim();
                    if (!itemText.isEmpty()) {
                        structuredText.append("- ").append(itemText).append("\n");
                    }
                }
                structuredText.append("\n");
            }
        }

        return structuredText.toString();
    }

    /**
     * Extrahiert und erweitert Metadaten aus dem Dokument
     */
    private void extractMetadata(Document doc, Metadata metadata) {
        // Meta-Tags extrahieren
        Elements metaTags = doc.select("meta[name], meta[property]");
        for (Element meta : metaTags) {
            String name = meta.hasAttr("name") ? meta.attr("name") : meta.attr("property");
            String content = meta.attr("content");
            if (!content.isEmpty()) {
                metadata.addValue("meta_" + name, content);
            }
        }

        // Alle Überschriften als Liste
        Elements headings = doc.select("h1, h2, h3");
        List<String> headingTexts = headings.stream()
                .map(Element::text)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toList());
        metadata.addValue("headings", String.join(" | ", headingTexts));

        // Hauptnavigation - gibt Struktur der Website
        Elements navItems = doc.select("nav a, .main-navigation a, .navbar a, .menu a");
        List<String> navTexts = navItems.stream()
                .map(Element::text)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.toList());
        metadata.addValue("navigation", String.join(" | ", navTexts));

        // Sprachen
        Elements langElements = doc.select("[lang], [hreflang], a[href*='lang=']");
        List<String> languages = new ArrayList<>();
        for (Element el : langElements) {
            if (el.hasAttr("lang")) languages.add(el.attr("lang"));
            if (el.hasAttr("hreflang")) languages.add(el.attr("hreflang"));
            if (el.hasAttr("href") && el.attr("href").contains("lang=")) {
                String lang = el.attr("href");
                lang = lang.substring(lang.indexOf("lang=") + 5);
                if (lang.contains("&")) lang = lang.substring(0, lang.indexOf("&"));
                languages.add(lang);
            }
        }
        if (!languages.isEmpty()) {
            metadata.addValue("languages", String.join(",", languages));
        }

        // Fakultät oder Bereich identifizieren
        Elements facultyElements = doc.select(".faculty, .department, .fachbereich");
        if (!facultyElements.isEmpty()) {
            String faculty = facultyElements.first().text().trim();
            metadata.addValue("faculty", faculty);
        }

        // Kontaktinformationen
        Elements contactElements = doc.select(".contact, .kontakt, address");
        if (!contactElements.isEmpty()) {
            String contact = contactElements.first().text().trim();
            metadata.addValue("contact", contact);
        }
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer decl) {
        decl.declare(new Fields("url", "events", "news", "courses", "text", "metadata"));
    }
}