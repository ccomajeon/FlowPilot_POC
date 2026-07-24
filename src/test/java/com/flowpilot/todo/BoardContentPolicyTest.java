package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BoardContentPolicyTest {
    private BoardContentPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new BoardContentPolicy(new ObjectMapper(), new BoardProperties(512 * 1024, 100_000));
    }

    @Test
    void acceptsSafeMarkdownAndVersionedRichText() {
        assertThatCode(() -> policy.validate(EditorType.MARKDOWN,
            "# 제목\n\n[안전한 링크](https://example.test/path)와 **본문**"))
            .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate(EditorType.RICH_TEXT, """
            {"schemaVersion":1,"type":"doc","content":[
              {"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"제목"}]},
              {"type":"paragraph","content":[{"type":"text","text":"링크","marks":[
                {"type":"link","attrs":{"href":"https://example.test"}}]}]}
            ]}
            """))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutableMarkdownAndExternalImages() {
        for (String unsafe : new String[] {
                "<script>alert(1)</script>",
                "<img src=x onerror=alert(1)>",
                "![tracking](https://example.test/pixel)",
                "[click](javascript:alert(1))",
                "[click](data:text/html,unsafe)"}) {
            assertThatThrownBy(() -> policy.validate(EditorType.MARKDOWN, unsafe))
                .isInstanceOf(ContentPolicyViolation.class);
        }
    }

    @Test
    void rejectsUnknownRichTextNodesAttributesAndDangerousUrls() {
        for (String unsafe : new String[] {
                "{\"schemaVersion\":2,\"type\":\"doc\",\"content\":[]}",
                "{\"schemaVersion\":1.5,\"type\":\"doc\",\"content\":[]}",
                "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[{\"type\":\"script\"}]}",
                "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"onclick\":\"x\",\"content\":[]}]}",
                "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\",\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}]}"}) {
            assertThatThrownBy(() -> policy.validate(EditorType.RICH_TEXT, unsafe))
                .isInstanceOf(ContentPolicyViolation.class);
        }
    }

    @Test
    void rejectsAmbiguousRichTextJsonDocuments() {
        for (String unsafe : new String[] {
                "{\"schemaVersion\":2,\"schemaVersion\":1,\"type\":\"doc\",\"content\":[]}",
                "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[]}"
                    + "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[]}"}) {
            assertThatThrownBy(() -> policy.validate(EditorType.RICH_TEXT, unsafe))
                .isInstanceOf(ContentPolicyViolation.class);
        }
    }
}
