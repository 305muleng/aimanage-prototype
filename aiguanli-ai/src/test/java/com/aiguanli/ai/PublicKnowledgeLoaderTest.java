package com.aiguanli.ai;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicKnowledgeLoaderTest {
    @Test
    void downloadsSelectedSourceWithoutModelCredentials() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var source = PublicKnowledgeCatalog.find("agile");
        String html = "<meta charset='utf-8'><main>可工作的软件。" + "持续交付。".repeat(30) + "</main>";
        var downloaded = response(200, Map.of("Content-Type", List.of("text/html")), html.getBytes(StandardCharsets.UTF_8));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(downloaded);
        var loaded = new PublicKnowledgeLoader(client).load(source);
        assertThat(loaded.text()).contains("可工作的软件");
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
        assertThat(request.getValue().uri()).isEqualTo(source.url());
        assertThat(request.getValue().headers().firstValue("Authorization")).isEmpty();
        assertThat(request.getValue().headers().firstValue("api-key")).isEmpty();
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(25));
    }

    @Test
    void rejectsOversizedBodiesAndHttpErrorsAndClosesStreams() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var source = PublicKnowledgeCatalog.find("agile");
        var oversized = response(200, Map.of("Content-Type", List.of("text/html")), new byte[4 * 1024 * 1024 + 1]);
        var forbidden = response(403, Map.of(), new byte[0]);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(oversized).thenReturn(forbidden);
        var loader = new PublicKnowledgeLoader(client);
        assertThatThrownBy(() -> loader.load(source)).isInstanceOf(IOException.class).hasMessageContaining("4 MiB");
        assertThatThrownBy(() -> loader.load(source)).isInstanceOf(IOException.class).hasMessageContaining("403");
        verify(oversized.body()).close();
        verify(forbidden.body()).close();
    }

    @Test
    void rejectsCrossOriginRedirectBeforeSendingSecondRequest() throws Exception {
        HttpClient client = mock(HttpClient.class);
        var redirected = response(302, Map.of("Location", List.of("https://other.example/content")), new byte[0]);
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any()))
                .thenReturn(redirected);
        assertThatThrownBy(() -> new PublicKnowledgeLoader(client).load(PublicKnowledgeCatalog.find("agile")))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTPS 来源");
        verify(client).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any());
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, Map<String, List<String>> headers, byte[] bytes) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
        var body = spy(new ByteArrayInputStream(bytes));
        when(response.body()).thenReturn(body);
        return response;
    }

    @Test
    void extractsArticleTextAndRetainsProvenanceWithoutNavigationOrScripts() throws Exception {
        var source = PublicKnowledgeCatalog.find("agile");
        String html = "<html><head><meta charset='utf-8'></head><body><nav>菜单噪声</nav>"
                + "<header>页眉噪声</header><main><h1>敏捷原则</h1><p>可工作的软件。"
                + "持续交付与团队协作的说明。".repeat(15)
                + "</p><script>secret-script</script><aside>广告噪声</aside></main><footer>页脚噪声</footer></body></html>";
        var loaded = PublicKnowledgeLoader.parse(source, html.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=utf-8", source.url(), Instant.parse("2026-10-11T00:00:00Z"));
        assertThat(loaded.text()).contains("可工作的软件", "敏捷原则")
                .doesNotContain("菜单噪声", "页眉噪声", "secret-script", "广告噪声", "页脚噪声");
        assertThat(loaded.source()).isEqualTo(source);
        assertThat(loaded.resolvedUrl()).isEqualTo(source.url().toString());
        assertThat(loaded.fetchedAt()).isEqualTo("2026-10-11T00:00:00Z");
    }

    @Test
    void extractsOnlySelectedNasaSectionAndRejectsChangedSectionBoundaries() throws Exception {
        var source = PublicKnowledgeCatalog.find("risk");
        String html = "<main><h2>6.3 Other</h2><p>unrelated previous section</p>"
                + "<h2>6.4.1 Risk Management Process Description</h2><p>Risk analysis and monitoring. "
                + "Risk assessment and mitigation. ".repeat(10)
                + "</p><h3>6.4.1.1 Inputs</h3><p>risk input</p>"
                + "<h2>6.4.2 Risk Management Process Guidance</h2><p>unrelated next section</p></main>";
        var loaded = PublicKnowledgeLoader.parse(source, html.getBytes(StandardCharsets.UTF_8),
                "text/html", source.url(), Instant.now());
        assertThat(loaded.text()).contains("Risk assessment", "6.4.1.1 Inputs")
                .doesNotContain("previous section", "next section", "6.4.2 Risk Management Process Guidance");
        assertThatThrownBy(() -> PublicKnowledgeLoader.parse(source,
                html.replace("6.4.2 Risk Management Process Guidance", "new heading").getBytes(StandardCharsets.UTF_8),
                "text/html", source.url(), Instant.now())).isInstanceOf(IOException.class).hasMessageContaining("章节标题");
    }

    @Test
    void rejectsAccessPagesAndUnexpectedResponseFormats() {
        var source = PublicKnowledgeCatalog.find("agile");
        assertThatThrownBy(() -> PublicKnowledgeLoader.parse(source,
                "<html>Please sign in</html>".getBytes(StandardCharsets.UTF_8), "text/html", source.url(), Instant.now()))
                .isInstanceOf(IOException.class).hasMessageContaining("预期正文");
        assertThatThrownBy(() -> PublicKnowledgeLoader.parse(source,
                "{}".getBytes(StandardCharsets.UTF_8), "application/json", source.url(), Instant.now()))
                .isInstanceOf(IOException.class).hasMessageContaining("HTML");
        var pdf = PublicKnowledgeCatalog.find("scrum");
        assertThatThrownBy(() -> PublicKnowledgeLoader.parse(pdf,
                "<html>Access denied</html>".getBytes(StandardCharsets.UTF_8), "text/html", pdf.url(), Instant.now()))
                .isInstanceOf(IOException.class).hasMessageContaining("PDF");
    }

    @Test
    void parsesDownloadedPdfBytesInMemory() throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Scrum guidance for Sprint planning and retrospective. ".repeat(3));
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        var source = PublicKnowledgeCatalog.find("scrum");
        assertThat(PublicKnowledgeLoader.parse(source, bytes, "application/pdf", source.url(), Instant.now()).text())
                .contains("Scrum guidance");
    }

    @Test
    void rejectsRedirectsOutsideChosenHttpsOrigin() throws Exception {
        URI original = PublicKnowledgeCatalog.find("agile").url();
        PublicKnowledgeLoader.requireAllowed(original, original);
        PublicKnowledgeLoader.requireAllowed(URI.create("https://www.agilemanifesto.org/another-page"), original);
        for (String url : new String[] { "http://agilemanifesto.org/", "https://127.0.0.1/",
                "https://agilemanifesto.org.evil.example/", "https://user@agilemanifesto.org/", "https://agilemanifesto.org:8443/" }) {
            assertThatThrownBy(() -> PublicKnowledgeLoader.requireAllowed(URI.create(url), original))
                    .isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> PublicKnowledgeCatalog.find("https://example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
