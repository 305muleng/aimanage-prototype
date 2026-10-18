package com.aiguanli.ai;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式开启的网页可访问性检查，不调用模型、不保存正文；普通测试不会联网。 */
@EnabledIfSystemProperty(named = "publicKnowledge.liveCheck", matches = "true")
class PublicKnowledgeLiveTest {
    @TestFactory
    Stream<DynamicTest> selectedPublicSourcesReturnReadableText() {
        return PublicKnowledgeCatalog.sources().stream().map(source -> DynamicTest.dynamicTest(source.id(), () -> {
            var loaded = new PublicKnowledgeLoader().load(source);
            assertThat(loaded.text()).hasSizeGreaterThan(100);
            System.out.println("Public source checked: " + source.id() + ", chars=" + loaded.text().length()
                    + ", url=" + loaded.resolvedUrl());
        }));
    }
}
