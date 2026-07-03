package engine.utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Json {

    private Json() {}

    public static String escape(String value) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        return builder.toString();
    }

    public static Object parse(String input) {
        Parser parser = new Parser(input);
        return parser.parseValue();
    }

    private static final class Parser {

        private final String source;
        private int position = 0;

        private Parser(String source) {
            this.source = source;
        }

        private Object parseValue() {
            skipWhitespace();
            char c = source.charAt(position);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == 't') {
                position += 4; // true
                return Boolean.TRUE;
            } else if (c == 'f') {
                position += 5; // false
                return Boolean.FALSE;
            } else if (c == 'n') {
                position += 4; // null
                return null;
            } else {
                return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            position++; // consume '{'
            skipWhitespace();
            if (source.charAt(position) == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                position++; // consume ':'
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                char next = source.charAt(position);
                position++; // consume ',' or '}'
                if (next == '}') {
                    break;
                }
            }
            return result;
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            position++; // consume '['
            skipWhitespace();
            if (source.charAt(position) == ']') {
                position++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char next = source.charAt(position);
                position++; // consume ',' or ']'
                if (next == ']') {
                    break;
                }
            }
            return result;
        }

        private String parseString() {
            StringBuilder builder = new StringBuilder();
            position++; // consume opening quote
            while (true) {
                char c = source.charAt(position++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char escaped = source.charAt(position++);
                    switch (escaped) {
                        case '"':
                            builder.append('"');
                            break;
                        case '\\':
                            builder.append('\\');
                            break;
                        case '/':
                            builder.append('/');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'u':
                            String hex = source.substring(position, position + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            position += 4;
                            break;
                        default:
                            builder.append(escaped);
                    }
                } else {
                    builder.append(c);
                }
            }
            return builder.toString();
        }

        private Object parseNumber() {
            int start = position;
            while (position < source.length() && isNumberChar(source.charAt(position))) {
                position++;
            }
            String number = source.substring(start, position);
            if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                return Double.parseDouble(number);
            }
            return Long.parseLong(number);
        }

        private boolean isNumberChar(char c) {
            return Character.isDigit(c) || c == '+' || c == '-' || c == '.' || c == 'e' || c == 'E';
        }

        private void skipWhitespace() {
            while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
                position++;
            }
        }
    }
}
