package com.eazy.batch.reader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streams the records of a JSON upload: a top-level array of flat objects
 * ({@code [ {"name":"Asha","age":21}, ... ]}), or an object holding one such array
 * ({@code {"students":[ ... ]}}). Values may be strings, numbers, booleans or null; a nested
 * object/array makes only that record invalid.
 *
 * <p>Self-contained on purpose (no JSON library needed), strict about syntax.</p>
 */
final class JsonRecordSource implements RecordSource {

    private final Reader in;
    private int peeked = -2;
    private boolean started;
    private boolean finished;
    private boolean firstRecord = true;

    JsonRecordSource(Reader reader) {
        this.in = reader instanceof BufferedReader ? reader : new BufferedReader(reader);
    }

    @Override
    public Record next() throws IOException {
        if (finished) return null;
        if (!started) {
            started = true;
            openArray();
        }

        skipWs();
        if (firstRecord) {
            firstRecord = false;
            if (peek() == ']') {
                read();
                finished = true;
                return null;
            }
        } else {
            int c = read();
            if (c == ']') {
                finished = true;
                return null;
            }
            if (c != ',') throw syntax("expected ',' or ']' between records");
            skipWs();
        }
        return readRecord();
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ─── structure ───────────────────────────────────────────────────

    private void openArray() throws IOException {
        skipWs();
        int c = read();
        if (c == '[') return;
        if (c != '{') throw syntax("the file must contain an array of records");

        // {"anything": ..., "records": [ ... ]} - use the first array property
        while (true) {
            skipWs();
            if (read() != '"') throw syntax("no array of records found in the top-level object");
            readStringBody();
            skipWs();
            if (read() != ':') throw syntax("expected ':' after a property name");
            skipWs();
            if (peek() == '[') {
                read();
                return;
            }
            skipValue();
            skipWs();
            int d = read();
            if (d != ',') throw syntax("no array of records found in the top-level object");
        }
    }

    private Record readRecord() throws IOException {
        if (read() != '{') throw syntax("each record must be an object");
        Map<String, String> values = new LinkedHashMap<>();
        String error = null;

        skipWs();
        if (peek() == '}') {
            read();
            return new Record(values, null);
        }
        while (true) {
            skipWs();
            if (read() != '"') throw syntax("expected a field name");
            String key = readStringBody();
            skipWs();
            if (read() != ':') throw syntax("expected ':' after the field name \"" + key + "\"");
            skipWs();

            String value = null;
            boolean nested = false;
            int c = peek();
            if (c == '"') {
                read();
                value = readStringBody();
            } else if (c == '{' || c == '[') {
                skipValue();
                nested = true;
            } else {
                String token = readToken();
                if (token.isEmpty()) throw syntax("missing value for \"" + key + "\"");
                value = token.equals("null") ? null : token;
            }

            if (nested && error == null) {
                error = "field \"" + key + "\" holds a nested object/array; only flat records are supported";
            } else if (!nested && values.containsKey(key) && error == null) {
                error = "field \"" + key + "\" appears more than once";
            }
            if (!nested) values.put(key, value);

            skipWs();
            int d = read();
            if (d == ',') continue;
            if (d == '}') break;
            throw syntax("expected ',' or '}' after the value of \"" + key + "\"");
        }
        return new Record(values, error);
    }

    // ─── values ──────────────────────────────────────────────────────

    /** Reads the rest of a string whose opening quote was already consumed. */
    private String readStringBody() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c < 0) throw syntax("unterminated string");
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append((char) c);
                continue;
            }
            int e = read();
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int h = Character.digit(read(), 16);
                        if (h < 0) throw syntax("bad \\u escape");
                        code = code * 16 + h;
                    }
                    sb.append((char) code);
                }
                default -> throw syntax("bad escape sequence");
            }
        }
    }

    /** A number or literal (true/false/null): everything up to a delimiter. */
    private String readToken() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = peek();
            if (c < 0 || c == ',' || c == '}' || c == ']' || Character.isWhitespace(c)) break;
            sb.append((char) read());
        }
        return sb.toString();
    }

    /** Consumes one value of any kind (used for nested values and ignored properties). */
    private void skipValue() throws IOException {
        int c = peek();
        if (c == '"') {
            read();
            readStringBody();
            return;
        }
        if (c != '{' && c != '[') {
            readToken();
            return;
        }
        int depth = 0;
        do {
            int d = read();
            if (d < 0) throw syntax("unexpected end of file");
            if (d == '"') {
                readStringBody();
            } else if (d == '{' || d == '[') {
                depth++;
            } else if (d == '}' || d == ']') {
                depth--;
            }
        } while (depth > 0);
    }

    // ─── characters ──────────────────────────────────────────────────

    private int peek() throws IOException {
        if (peeked == -2) peeked = in.read();
        return peeked;
    }

    private int read() throws IOException {
        int c = peek();
        peeked = -2;
        return c;
    }

    private void skipWs() throws IOException {
        while (true) {
            int c = peek();
            if (c == 0xFEFF || (c >= 0 && Character.isWhitespace(c))) read();
            else return;
        }
    }

    private static IOException syntax(String message) {
        return new IOException("Invalid JSON: " + message);
    }
}
