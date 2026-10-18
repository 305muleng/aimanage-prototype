package com.aiguanli.ai;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.ByteArrayResource;

/** 下载与正文解析全部在内存完成；不会携带模型 API Key。 */
public class PublicKnowledgeLoader {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final ScheduledExecutorService BODY_TIMEOUT = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "public-knowledge-download-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final HttpClient client;

    public PublicKnowledgeLoader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    PublicKnowledgeLoader(HttpClient client) { this.client = client; }

    public LoadedSource load(PublicKnowledgeCatalog.Source source) throws IOException {
        try {
            URI current = source.url();
            for (int redirects = 0; redirects <= 3; redirects++) {
                requireAllowed(current, source.url());
                var request = HttpRequest.newBuilder(current).timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "Aiguanli-KnowledgeReader/1.0")
                        .header("Accept", "text/html,application/pdf,text/plain;q=0.8")
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (var input = response.body()) {
                    if (response.statusCode() >= 300 && response.statusCode() < 400) {
                        String location = response.headers().firstValue("Location")
                                .orElseThrow(() -> new IOException("资料站点重定向缺少地址。"));
                        current = current.resolve(location);
                        continue;
                    }
                    if (response.statusCode() != 200) {
                        throw new IOException("资料网站返回 HTTP " + response.statusCode() + "，本次未导入。");
                    }
                    // 先在内存中限量读取。解析器不加载网页图片、脚本或链接资源。
                    var timeout = BODY_TIMEOUT.schedule(() -> {
                        try { input.close(); } catch (IOException ignored) { }
                    }, 25, TimeUnit.SECONDS);
                    byte[] bytes;
                    try { bytes = input.readNBytes(MAX_BYTES + 1); }
                    finally { timeout.cancel(false); }
                    if (bytes.length > MAX_BYTES) { throw new IOException("资料超过 4 MiB，未导入。请缩小资料范围。"); }
                    String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    return parse(source, bytes, type, current, Instant.now());
                }
            }
            throw new IOException("资料重定向次数过多，本次未导入。");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("资料下载被中断，本次未导入。", ex);
        }
    }

    static void requireAllowed(URI target, URI original) throws IOException {
        if (!"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null
                || target.getPort() != -1 || target.getHost() == null
                || !host(target).equals(host(original))) {
            throw new IOException("资料地址或重定向超出选定的 HTTPS 来源，本次未导入。");
        }
    }

    private static String host(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    static LoadedSource parse(PublicKnowledgeCatalog.Source source, byte[] bytes, String type,
            URI resolvedUrl, Instant fetchedAt) throws IOException {
        String text;
        if (source.format().equals("PDF")) {
            if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
                throw new IOException("来源没有返回 PDF，可能是访问验证页。本次未导入。");
            }
            text = new TikaDocumentReader(new ByteArrayResource(bytes) {
                @Override public String getFilename() { return source.id() + ".pdf"; }
            }).get().stream().map(Document::getText).filter(t -> t != null)
                    .reduce("", (a, b) -> a + "\n" + b);
        } else {
            if (!type.contains("text/html") && !type.contains("application/xhtml+xml")) {
                throw new IOException("来源没有返回 HTML 正文，本次未导入。");
            }
            var document = Jsoup.parse(new ByteArrayInputStream(bytes), null, resolvedUrl.toString());
            document.select("script,style,nav,header,footer,aside,iframe,noscript,form,[role=navigation],"
                    + "[hidden],.cookie-banner,.toc,.table-of-contents").remove();
            Element content = document.selectFirst("main");
            if (content == null) { content = document.selectFirst("article"); }
            if (content == null) { content = document.body(); }
            text = extract(content, source.startHeading(), source.endHeading());
        }
        text = text.replace('\u00a0', ' ').replaceAll("[\\t ]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").strip();
        if (text.length() < 100 || !text.toLowerCase(Locale.ROOT).contains(source.expectedText().toLowerCase(Locale.ROOT))) {
            throw new IOException("未提取到预期正文，网站可能已调整或需要验证。本次未导入。");
        }
        return new LoadedSource(source, text, resolvedUrl.toString(), fetchedAt.toString());
    }

    private static String extract(Element root, String startHeading, String endHeading) throws IOException {
        Element start = heading(root, startHeading);
        Element end = heading(root, endHeading);
        if ((!startHeading.isEmpty() && start == null) || (!endHeading.isEmpty() && end == null)) {
            throw new IOException("资料章节标题已变化，未导入整页以免混入其他章节。");
        }
        StringBuilder result = new StringBuilder();
        boolean[] active = { start == null };
        boolean[] ended = { false };
        NodeTraversor.traverse(new NodeVisitor() {
            @Override public void head(Node node, int depth) {
                if (node == end) { active[0] = false; ended[0] = true; }
                if (node == start && !ended[0]) { active[0] = true; }
                if (active[0]) {
                    if (node instanceof TextNode text) { result.append(text.getWholeText()); }
                    else if (node instanceof Element element && (element.isBlock() || element.tagName().equals("br"))) {
                        result.append('\n');
                    }
                }
            }
            @Override public void tail(Node node, int depth) {
                if (active[0] && node instanceof Element element && element.isBlock()) { result.append('\n'); }
            }
        }, root);
        return result.toString();
    }

    private static Element heading(Element root, String name) {
        if (name.isEmpty()) { return null; }
        return root.select("h1,h2,h3,h4,h5,h6").stream().filter(e -> e.text().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public record LoadedSource(PublicKnowledgeCatalog.Source source, String text, String resolvedUrl, String fetchedAt) {}
}
