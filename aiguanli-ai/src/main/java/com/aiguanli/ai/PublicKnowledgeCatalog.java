package com.aiguanli.ai;

import java.net.URI;
import java.util.List;

/** 仅保存经人工核对的公开资料地址，不随程序打包网页正文或向量。 */
public final class PublicKnowledgeCatalog {
    private PublicKnowledgeCatalog() {}

    private static final String NASA = "https://www.nasa.gov/reference/6-0-crosscutting-technical-management/";
    private static final List<Source> SOURCES = List.of(
            new Source("agile", "敏捷宣言十二原则", "敏捷宣言签署者", "中文官方页面",
                    URI.create("https://agilemanifesto.org/iso/zhchs/principles.html"),
                    "敏捷价值、交付与团队协作", "HTML", "", "", "可工作的软件", "许可见原文页面"),
            new Source("scrum", "Scrum 指南（简体中文）", "Ken Schwaber / Jeff Sutherland；译者见原文", "2020-11",
                    URI.create("https://scrumguides.org/docs/scrumguide/v2020/2020-Scrum-Guide-Chinese-Simplified.pdf"),
                    "Sprint、职责、Backlog、完成的定义", "PDF", "", "", "Scrum", "CC BY-SA 4.0；保留原作者和译者署名"),
            new Source("stories", "User Stories", "Agile Alliance", "在线页面（以抓取时间为准）",
                    URI.create("https://agilealliance.org/glossary/user-stories/"),
                    "用户故事、拆分与需求沟通", "HTML", "", "", "user stories", "许可见原文页面"),
            new Source("kanban", "The Kanban Guide", "Kanban Guides", "2025.5",
                    URI.create("https://kanbanguides.org/the-kanban-guide/2025.5/"),
                    "在制品、吞吐量、工作项年龄、周期时间", "HTML", "", "", "cycle time", "许可及署名见原文 License / Acknowledgements"),
            new Source("acceptance", "Gherkin Reference", "Cucumber", "在线页面（以抓取时间为准）",
                    URI.create("https://cucumber.io/docs/gherkin/reference/"),
                    "Given / When / Then 验收场景", "HTML", "", "", "Given", "许可见原文页面"),
            new Source("risk", "Systems Engineering Handbook：技术风险管理流程", "NASA", "在线手册第 6.4.1 节",
                    URI.create(NASA), "风险识别、评估、缓解与跟踪", "HTML",
                    "6.4.1 Risk Management Process Description", "6.4.2 Risk Management Process Guidance", "risk", "使用与署名要求见 NASA 原文"),
            new Source("change", "Systems Engineering Handbook：配置管理流程", "NASA", "在线手册第 6.5.1 节",
                    URI.create(NASA), "配置基线、变更控制与版本追踪", "HTML",
                    "6.5.1 Process Description", "6.5.2 CM Guidance", "configuration", "使用与署名要求见 NASA 原文")
    );

    public static List<Source> sources() { return SOURCES; }

    public static Source find(String id) {
        return SOURCES.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知公共资料编号，请先输入 /sources 查看。"));
    }

    public record Source(String id, String title, String publisher, String version, URI url,
            String topics, String format, String startHeading, String endHeading,
            String expectedText, String licenseNotice) {}
}
