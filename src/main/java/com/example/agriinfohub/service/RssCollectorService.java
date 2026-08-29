package com.example.agriinfohub.service;

import com.example.agriinfohub.entity.AgricultureArticle;
import com.example.agriinfohub.repository.AgricultureArticleRepository;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Service
public class RssCollectorService {

    private static final Logger log = LoggerFactory.getLogger(RssCollectorService.class);
    private static final ZoneId JAPAN_ZONE = ZoneId.of("Asia/Tokyo");

    private static final List<FeedDefinition> FEEDS = List.of(
            new FeedDefinition(
                    "農林水産省",
                    "全国",
                    "市場・流通・ニュース",
                    "https://www.maff.go.jp/j/press/rss.xml"),
            new FeedDefinition(
                    "千葉県 印旛農業事務所",
                    "千葉県",
                    "栽培技術・研修・求人",
                    "https://www.pref.chiba.lg.jp/ap-inba/shinchaku.xml"));

    private final AgricultureArticleRepository articleRepository;
    private final HttpClient httpClient;

    public RssCollectorService(AgricultureArticleRepository articleRepository) {
        this.articleRepository = articleRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Scheduled(
            initialDelayString = "${agri.feed.initial-delay-ms:3000}",
            fixedDelayString = "${agri.feed.interval-ms:3600000}")
    public void collectAll() {
        log.info("RSS_COLLECT_START feeds={}", FEEDS.size());
        int totalSaved = 0;
        for (FeedDefinition feed : FEEDS) {
            totalSaved += collect(feed);
        }
        log.info("RSS_COLLECT_END totalSaved={}", totalSaved);
    }

    private int collect(FeedDefinition feed) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(feed.url()))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
                .header("User-Agent", "AgriInfoHub/1.0")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("unknown");
            log.info("RSS_HTTP source={}, status={}, contentType={}",
                    feed.sourceName(), response.statusCode(), contentType);

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("RSS_HTTP_ERROR source={}, status={}",
                        feed.sourceName(), response.statusCode());
                response.body().close();
                return 0;
            }

            try (InputStream body = response.body()) {
                List<FeedItem> items = parse(body);
                int saved = 0;
                for (FeedItem item : items) {
                    if (saveIfNew(feed, item)) {
                        saved++;
                    }
                }
                log.info("RSS_PARSE source={}, items={}, saved={}",
                        feed.sourceName(), items.size(), saved);
                return saved;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("RSS_INTERRUPTED source={}", feed.sourceName());
            return 0;
        } catch (Exception ex) {
            log.warn("RSS_ERROR source={}, type={}, message={}",
                    feed.sourceName(), ex.getClass().getName(), ex.getMessage(), ex);
            return 0;
        }
    }

    private List<FeedItem> parse(InputStream stream) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        Document document = factory.newDocumentBuilder().parse(stream);
        NodeList entries = document.getElementsByTagNameNS("*", "item");
        if (entries.getLength() == 0) {
            entries = document.getElementsByTagName("item");
        }
        if (entries.getLength() == 0) {
            entries = document.getElementsByTagNameNS("*", "entry");
        }

        List<FeedItem> result = new ArrayList<>();
        for (int index = 0; index < entries.getLength(); index++) {
            Node node = entries.item(index);
            if (!(node instanceof Element element)) {
                continue;
            }

            String title = text(element, "title");
            String url = link(element);
            if (title.isBlank() || url.isBlank()) {
                continue;
            }

            String summary = firstNonBlank(
                    text(element, "description"),
                    text(element, "summary"),
                    text(element, "content"));
            String published = firstNonBlank(
                    text(element, "pubDate"),
                    text(element, "published"),
                    text(element, "updated"),
                    text(element, "date"));

            result.add(new FeedItem(
                    clean(title, 500),
                    url.strip(),
                    clean(summary, 1800),
                    parsePublishedAt(published)));
        }
        return result;
    }

    private boolean saveIfNew(FeedDefinition feed, FeedItem item) {
        String urlHash = sha256(item.url());
        if (articleRepository.existsByUrlHash(urlHash)) {
            return false;
        }

        AgricultureArticle article = new AgricultureArticle();
        article.setTitle(item.title());
        article.setUrl(item.url());
        article.setUrlHash(urlHash);
        article.setSourceName(feed.sourceName());
        article.setRegion(feed.region());
        article.setCategory(detectCategory(item.title() + " " + item.summary(), feed.defaultCategory()));
        article.setSummary(item.summary());
        article.setPublishedAt(item.publishedAt());
        article.setFetchedAt(LocalDateTime.now(JAPAN_ZONE));
        articleRepository.save(article);
        return true;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    private String detectCategory(String value, String defaultCategory) {
        String text = value.toLowerCase(Locale.JAPANESE);

        if (containsAny(text, "後継", "継承", "担い手", "移譲")) {
            return "担い手・経営継承";
        }
        if (containsAny(text, "災害", "台風", "大雨", "大雪", "高温", "病害虫", "防除", "警報", "注意報")) {
            return "天気・災害・病害虫";
        }
        if (containsAny(text, "補助金", "交付金", "支援事業", "融資", "公募", "助成")) {
            return "農政・補助金";
        }
        if (containsAny(text, "研修", "求人", "就農", "栽培", "技術", "講習", "セミナー")) {
            return "栽培技術・研修・求人";
        }
        if (containsAny(text, "価格", "市場", "流通", "出荷", "販売", "統計")) {
            return "市場・流通・ニュース";
        }
        return defaultCategory;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String link(Element element) {
        NodeList links = element.getElementsByTagNameNS("*", "link");
        for (int index = 0; index < links.getLength(); index++) {
            Node node = links.item(index);
            if (node instanceof Element linkElement) {
                String href = linkElement.getAttribute("href");
                if (!href.isBlank()) {
                    return href;
                }
                String value = linkElement.getTextContent();
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String text(Element element, String localName) {
        NodeList namespaced = element.getElementsByTagNameNS("*", localName);
        if (namespaced.getLength() > 0) {
            return value(namespaced.item(0));
        }

        NodeList plain = element.getElementsByTagName(localName);
        return plain.getLength() > 0 ? value(plain.item(0)) : "";
    }

    private String value(Node node) {
        String value = node == null ? "" : node.getTextContent();
        return value == null ? "" : value.strip();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String clean(String value, int maxLength) {
        String withoutTags = value == null ? "" : value.replaceAll("<[^>]+>", " ");
        String cleaned = HtmlUtils.htmlUnescape(withoutTags)
                .replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .strip();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength) + "…";
    }

    private LocalDateTime parsePublishedAt(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now(JAPAN_ZONE);
        }

        List<DateParser> parsers = List.of(
                raw -> ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
                raw -> OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(),
                Instant::parse);

        for (DateParser parser : parsers) {
            try {
                return LocalDateTime.ofInstant(parser.parse(value.strip()), JAPAN_ZONE);
            } catch (DateTimeParseException ignored) {
                // 次の形式を試す
            }
        }
        return LocalDateTime.now(JAPAN_ZONE);
    }

    private record FeedDefinition(
            String sourceName,
            String region,
            String defaultCategory,
            String url) {
    }

    private record FeedItem(
            String title,
            String url,
            String summary,
            LocalDateTime publishedAt) {
    }

    @FunctionalInterface
    private interface DateParser {
        Instant parse(String value) throws DateTimeParseException;
    }
}
