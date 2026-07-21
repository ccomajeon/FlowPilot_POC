package com.flowpilot.todo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

@Component
class BoardContentPolicy {
    private static final int MAX_RICH_TEXT_DEPTH = 64;
    private static final int MAX_RICH_TEXT_NODES = 10_000;
    private static final Set<String> BLOCK_TYPES = Set.of(
        "paragraph", "heading", "bulletList", "orderedList");
    private static final Set<String> INLINE_TYPES = Set.of("text", "hardBreak");
    private final Parser markdownParser = Parser.builder().build();
    private final ObjectMapper objectMapper;

    BoardContentPolicy(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void validate(EditorType editorType, String content) {
        if (editorType == null || content == null || content.isBlank() || content.length() > 100_000) {
            throw new ContentPolicyViolation();
        }
        if (editorType == EditorType.MARKDOWN) validateMarkdown(content);
        else validateRichText(content);
    }

    private void validateMarkdown(String content) {
        Node document = markdownParser.parse(content);
        for (Node node = document.getFirstChild(); node != null; node = next(document, node)) {
            if (node instanceof HtmlBlock || node instanceof HtmlInline || node instanceof Image) {
                throw new ContentPolicyViolation();
            }
            if (node instanceof Link link) validateUrl(link.getDestination());
        }
    }

    private static Node next(Node root, Node current) {
        if (current.getFirstChild() != null) return current.getFirstChild();
        while (current != root && current.getNext() == null) current = current.getParent();
        return current == root ? null : current.getNext();
    }

    private void validateRichText(String content) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new ContentPolicyViolation();
        }
        Counter counter = new Counter();
        requireObject(root, Set.of("schemaVersion", "type", "content"));
        if (!root.path("schemaVersion").canConvertToInt() || root.path("schemaVersion").intValue() != 1
                || !"doc".equals(text(root, "type"))) {
            throw new ContentPolicyViolation();
        }
        validateChildren(root.get("content"), BLOCK_TYPES, 1, counter);
    }

    private void validateNode(JsonNode node, Set<String> expectedTypes, int depth, Counter counter) {
        if (depth > MAX_RICH_TEXT_DEPTH || ++counter.value > MAX_RICH_TEXT_NODES) {
            throw new ContentPolicyViolation();
        }
        if (!node.isObject()) throw new ContentPolicyViolation();
        String type = text(node, "type");
        if (!expectedTypes.contains(type)) throw new ContentPolicyViolation();

        switch (type) {
            case "paragraph" -> {
                requireObject(node, Set.of("type", "content"));
                validateChildren(node.get("content"), INLINE_TYPES, depth + 1, counter);
            }
            case "heading" -> {
                requireObject(node, Set.of("type", "attrs", "content"));
                JsonNode attrs = requireObject(node.get("attrs"), Set.of("level"));
                int level = attrs.path("level").asInt(-1);
                if (!attrs.path("level").isIntegralNumber() || level < 1 || level > 6) {
                    throw new ContentPolicyViolation();
                }
                validateChildren(node.get("content"), INLINE_TYPES, depth + 1, counter);
            }
            case "bulletList" -> {
                requireObject(node, Set.of("type", "content"));
                validateChildren(node.get("content"), Set.of("listItem"), depth + 1, counter);
            }
            case "orderedList" -> {
                requireObject(node, Set.of("type", "attrs", "content"));
                JsonNode attrs = requireObject(node.get("attrs"), Set.of("order"));
                int order = attrs.path("order").asInt(-1);
                if (!attrs.path("order").isIntegralNumber() || order < 1 || order > 1_000_000) {
                    throw new ContentPolicyViolation();
                }
                validateChildren(node.get("content"), Set.of("listItem"), depth + 1, counter);
            }
            case "listItem" -> {
                requireObject(node, Set.of("type", "content"));
                validateChildren(node.get("content"), BLOCK_TYPES, depth + 1, counter);
            }
            case "text" -> validateText(node);
            case "hardBreak" -> requireObject(node, Set.of("type"));
            default -> throw new ContentPolicyViolation();
        }
    }

    private void validateText(JsonNode node) {
        requireObject(node, Set.of("type", "text", "marks"));
        JsonNode text = node.get("text");
        if (text == null || !text.isTextual() || text.textValue().isEmpty()) throw new ContentPolicyViolation();
        JsonNode marks = node.get("marks");
        if (marks == null) return;
        if (!marks.isArray()) throw new ContentPolicyViolation();
        for (JsonNode mark : marks) {
            if (!mark.isObject()) throw new ContentPolicyViolation();
            String type = text(mark, "type");
            if (Set.of("bold", "italic", "code").contains(type)) {
                requireObject(mark, Set.of("type"));
            } else if ("link".equals(type)) {
                requireObject(mark, Set.of("type", "attrs"));
                JsonNode attrs = requireObject(mark.get("attrs"), Set.of("href"));
                validateUrl(text(attrs, "href"));
            } else {
                throw new ContentPolicyViolation();
            }
        }
    }

    private void validateChildren(JsonNode children, Set<String> expectedTypes, int depth, Counter counter) {
        if (children == null || !children.isArray()) throw new ContentPolicyViolation();
        for (JsonNode child : children) validateNode(child, expectedTypes, depth, counter);
    }

    private static JsonNode requireObject(JsonNode node, Set<String> allowedFields) {
        if (node == null || !node.isObject()) throw new ContentPolicyViolation();
        node.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) throw new ContentPolicyViolation();
        });
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) throw new ContentPolicyViolation();
        return value.textValue();
    }

    private static void validateUrl(String destination) {
        if (destination == null || destination.isBlank() || destination.startsWith("//")
                || destination.indexOf('\\') >= 0 || destination.chars().anyMatch(Character::isISOControl)) {
            throw new ContentPolicyViolation();
        }
        if (destination.startsWith("/") || destination.startsWith("#")) return;
        try {
            URI uri = URI.create(destination.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !Set.of("http", "https", "mailto").contains(scheme.toLowerCase(Locale.ROOT))) {
                throw new ContentPolicyViolation();
            }
        } catch (IllegalArgumentException exception) {
            throw new ContentPolicyViolation();
        }
    }

    private static final class Counter { int value; }
}

class ContentPolicyViolation extends RuntimeException {}
