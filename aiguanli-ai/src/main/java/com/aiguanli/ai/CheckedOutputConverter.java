package com.aiguanli.ai;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.converter.BeanOutputConverter;

/** 保留 Spring AI 的结构提示；相同重复值可合并，冲突值绝不按“最后一个值”覆盖。 */
final class CheckedOutputConverter<T> extends BeanOutputConverter<T> {
    private final Class<T> type;
    private final ObjectMapper mapper;

    CheckedOutputConverter(Class<T> type, ObjectMapper mapper) {
        super(type, mapper);
        this.type = type;
        this.mapper = mapper;
    }

    @Override
    public T convert(String text) {
        if (text == null || text.isBlank()) { throw invalid(); }
        try (JsonParser parser = mapper.getFactory().createParser(unfence(text))) {
            // 自己逐字段比较，不启用 Jackson 的一律拒绝重复策略。
            parser.disable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            parser.nextToken();
            JsonNode root = readNode(parser, 0);
            if (!root.isObject() || parser.nextToken() != null) { throw invalid(); }
            return mapper.treeToValue(root, type);
        } catch (IOException | IllegalArgumentException ex) {
            // 供应商输出和 Jackson 异常可能包含业务全文，不记日志，也不挂到异常链。
            throw invalid();
        }
    }

    private JsonNode readNode(JsonParser parser, int depth) throws IOException {
        if (depth > 64 || parser.currentToken() == null) { throw invalid(); }
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (parser.currentToken()) {
            case START_OBJECT -> {
                ObjectNode object = nodes.objectNode();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.FIELD_NAME) { throw invalid(); }
                    String name = parser.currentName();
                    parser.nextToken();
                    JsonNode value = readNode(parser, depth + 1);
                    JsonNode previous = object.get(name);
                    if (previous != null && !previous.equals(value)) {
                        throw new InvalidOutputException("模型结构化输出含值冲突的重复字段，本次结果未采用。");
                    }
                    object.set(name, value);
                }
                yield object;
            }
            case START_ARRAY -> {
                ArrayNode array = nodes.arrayNode();
                while (parser.nextToken() != JsonToken.END_ARRAY) { array.add(readNode(parser, depth + 1)); }
                yield array;
            }
            case VALUE_STRING -> nodes.textNode(parser.getText());
            case VALUE_TRUE -> nodes.booleanNode(true);
            case VALUE_FALSE -> nodes.booleanNode(false);
            case VALUE_NULL -> nodes.nullNode();
            case VALUE_NUMBER_INT -> nodes.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> nodes.numberNode(parser.getDecimalValue());
            default -> throw invalid();
        };
    }

    private static String unfence(String text) {
        String value = text.strip();
        if (value.startsWith("```")) {
            int end = value.indexOf('\n');
            if (end < 0 || !value.endsWith("```")) { throw invalid(); }
            String heading = value.substring(0, end).strip();
            if (!heading.equals("```") && !heading.equalsIgnoreCase("```json")) { throw invalid(); }
            value = value.substring(end + 1, value.length() - 3).strip();
        }
        return value;
    }

    private static InvalidOutputException invalid() {
        return new InvalidOutputException("模型结构化输出格式无效，本次结果未采用。可重试当前命令。");
    }

    static final class InvalidOutputException extends IllegalStateException {
        private InvalidOutputException(String message) { super(message); }
    }
}
