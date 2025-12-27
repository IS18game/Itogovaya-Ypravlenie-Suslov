package org.example.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.example.model.ContactInfo;
import org.example.repository.ContactInfoRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CrawlerService {
    private final ExecutorService executor;
    private final ContactInfoRepository repo;
    private final RestTemplate restTemplate;

    private final Set<String> visited = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<String> frontier = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pagesProcessed = new AtomicInteger(0);

    private static final Pattern EMAIL = Pattern.compile("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{7,}\\d");
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b(\\d{6},?\\s*)?(г\\.?\\s*)?[А-Яа-яёЁ\\-\\s]+,?\\s*(ул\\.|улица|проспект|пр\\.|пер\\.|переулок|наб\\.|набережная|шоссе|ш\\.|бульвар|бул\\.|пл\\.|площадь)\\s+[А-Яа-яёЁ\\-\\s]+,?\\s*(д\\.|дом)?\\s*\\d+[А-Яа-я]?(\\s*корп\\.?\\s*\\d+)?"
    );

    private final Timer parsingTimer;
    private final Counter successCounter;
    private final Counter errorCounter;
    private final Counter savedCounter;

    private final Tracer tracer;

    public CrawlerService(@Qualifier("crawlerExecutor") ExecutorService executor,
                          ContactInfoRepository repo,
                          RestTemplate restTemplate,
                          MeterRegistry meterRegistry,
                          Tracer tracer) {
        this.executor = executor;
        this.repo = repo;
        this.restTemplate = restTemplate;
        this.parsingTimer = meterRegistry.timer("parser.parsing.time");
        this.successCounter = meterRegistry.counter("parser.success");
        this.errorCounter = meterRegistry.counter("parser.error");
        this.savedCounter = meterRegistry.counter("parser.saved");
        this.tracer = tracer;
    }

    public void submitSeeds(Set<String> seeds, int maxPages, int maxDepth) {
        frontier.addAll(seeds);
        visited.addAll(seeds);
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            executor.submit(() -> crawlLoop(maxPages, maxDepth));
        }
    }

    public void start(String url) {
        Set<String> seeds = Set.of(url);
        int maxPages = 100; // или сколько нужно
        int maxDepth = 3;   // если используешь глубину

        submitSeeds(seeds, maxPages, maxDepth);
    }

    private void crawlLoop(int maxPages, int maxDepth) {
        while (pagesProcessed.get() < maxPages) {
            String url = frontier.poll();
            if (url == null) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            try {
                processUrl(url);
                if (pagesProcessed.incrementAndGet() >= maxPages) break;
            } catch (Exception ignored) {}
        }
    }

    @Transactional
    private void processUrl(String url) {
        Span span = tracer.spanBuilder("Process URL")
                .setAttribute("url", url)
                .startSpan();
        try (Scope scope = span.makeCurrent()) {
            System.out.println("➡️ Processing: " + url);
            parsingTimer.record(() -> {
                try {
                    String html = restTemplate.getForObject(URI.create(url), String.class);
                    if (html == null || html.isBlank()) {
                        System.out.println("⚠️ Empty or null HTML for: " + url);
                        errorCounter.increment();
                        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, "Empty HTML");
                        return;
                    }

                    System.out.println("✅ HTML loaded (" + html.length() + " chars)");
                    span.setAttribute("html.length", html.length());

                    Document doc = Jsoup.parse(html, url);
                    String text = doc.text();

                    Set<String> seenEmails = new HashSet<>();
                    int itemsSaved = 0;

                    Matcher mEmail = EMAIL.matcher(text);
                    while (mEmail.find()) {
                        String email = mEmail.group().toLowerCase().trim();

                        if (!email.isBlank() &&
                                seenEmails.add(email) &&
                                !repo.existsBySourceUrlAndEmail(url, email)) {
                            ContactInfo info = new ContactInfo();
                            info.setSourceUrl(url);
                            info.setEmail(email);
                            repo.save(info);
                            System.out.println("📧 Найдена эл. почта: " + email);
                            itemsSaved++;
                        }
                    }

                    Set<String> seenPhones = new HashSet<>();

                    Matcher mPhone = PHONE.matcher(text);
                    while (mPhone.find()) {
                        String phone = mPhone.group();
                        // оставляем основной парсинг ниже
                    }

                    Set<String> seenAddresses = new HashSet<>();

                    Matcher mAddr = ADDRESS.matcher(text);
                    while (mAddr.find()) {
                        String addr = mAddr.group().trim();
                        if (!addr.isBlank() &&
                                seenAddresses.add(addr) &&
                                !repo.existsBySourceUrlAndAddress(url, addr)) {
                            ContactInfo info = new ContactInfo();
                            info.setSourceUrl(url);
                            info.setAddress(addr);
                            repo.save(info);
                            System.out.println("📍Найден адресс: " + addr);
                            itemsSaved++;
                        }
                    }

                    Elements links = doc.select("a[href]");
                    long added = links.stream()
                            .map(e -> e.absUrl("href"))
                            .filter(h -> h.startsWith("http"))
                            .filter(h -> !h.contains("#"))
                            .filter(h -> visited.add(h))
                            .peek(frontier::add)
                            .count();

                    System.out.println("🔗 Links added to frontier: " + added);
                    span.setAttribute("links.added", added);
                    span.setAttribute("items.saved", itemsSaved);

                    if (itemsSaved > 0) savedCounter.increment(itemsSaved);
                    successCounter.increment();

                } catch (Exception e) {
                    System.out.println("❌ Error processing " + url + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    errorCounter.increment();
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
                    span.recordException(e);
                }
            });
        } finally {
            span.end();
        }
    }

    public PageResult getResults(int page, int size, String sortBy) {
        var all = repo.findAll();
        var stream = all.parallelStream();
        if ("email".equalsIgnoreCase(sortBy)) stream = stream.sorted((a,b)-> nullSafe(a.getEmail()).compareTo(nullSafe(b.getEmail())));
        if ("phone".equalsIgnoreCase(sortBy)) stream = stream.sorted((a,b)-> nullSafe(a.getPhone()).compareTo(nullSafe(b.getPhone())));
        var list = stream.skip((long) page * size).limit(size).toList();
        return new PageResult(list, all.size());
    }

    private String nullSafe(String s){ return s==null?"":s; }

    public record PageResult(java.util.List<ContactInfo> items, int total) {}
}