package com.achpayments.nacha;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.achpayments.nacha.AchFile.Addenda;
import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.BatchControl;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileControl;
import com.achpayments.nacha.AchFile.FileHeader;

/**
 * Parses NACHA file text into an {@link AchFile}.
 * Checks structure only: record length, record order and numeric fields.
 * Totals, entry hash and routing check digits are checked by the validator.
 */
public final class AchFileParser {

    private static final int RECORD_LENGTH = 94;
    private static final String PADDING = "9".repeat(RECORD_LENGTH);
    private static final Map<Character, String> RECORD_NAMES = Map.of(
            '1', "File Header", '5', "Batch Header", '6', "Entry Detail",
            '7', "Addenda", '8', "Batch Control", '9', "File Control");

    private final List<String> lines;
    private int index;

    private AchFileParser(List<String> lines) {
        this.lines = lines;
    }

    public static AchFile parse(String content) {
        return new AchFileParser(content.lines().toList()).parseFile();
    }

    private AchFile parseFile() {
        FileHeader header = fileHeader(next('1'));
        List<Batch> batches = new ArrayList<>();
        while (peekType() == '5') {
            batches.add(batch());
        }
        expectOneOf('5', '9');
        FileControl control = fileControl(next('9'));
        for (; index < lines.size(); index++) {
            if (!lines.get(index).equals(PADDING)) {
                throw new AchParseException(index + 1, "only 9-filler records may follow the File Control record");
            }
        }
        return new AchFile(header, batches, control);
    }

    private Batch batch() {
        BatchHeader header = batchHeader(next('5'));
        List<Entry> entries = new ArrayList<>();
        while (peekType() == '6') {
            entries.add(entry(next('6')));
        }
        expectOneOf('6', '8');
        return new Batch(header, entries, batchControl(next('8')));
    }

    private FileHeader fileHeader(Line l) {
        return new FileHeader(l.alpha(2, 3), l.alpha(4, 13), l.alpha(14, 23), l.alpha(24, 29), l.alpha(30, 33),
                l.alpha(34, 34), l.alpha(35, 37), l.alpha(38, 39), l.alpha(40, 40), l.alpha(41, 63),
                l.alpha(64, 86), l.alpha(87, 94));
    }

    private BatchHeader batchHeader(Line l) {
        return new BatchHeader(l.alpha(2, 4), l.alpha(5, 20), l.alpha(21, 40), l.alpha(41, 50), l.alpha(51, 53),
                l.alpha(54, 63), l.alpha(64, 69), l.alpha(70, 75), l.alpha(76, 78), l.alpha(79, 79),
                l.alpha(80, 87), l.num(88, 94));
    }

    private Entry entry(Line l) {
        List<Addenda> addenda = new ArrayList<>();
        while (peekType() == '7') {
            Line a = next('7');
            addenda.add(new Addenda(a.alpha(2, 3), a.text().substring(3)));
        }
        return new Entry(l.alpha(2, 3), l.alpha(4, 11), l.alpha(12, 12), l.alpha(13, 29), l.num(30, 39),
                l.alpha(40, 54), l.alpha(55, 76), l.alpha(77, 78), l.alpha(79, 79), l.alpha(80, 94),
                List.copyOf(addenda));
    }

    private BatchControl batchControl(Line l) {
        return new BatchControl(l.alpha(2, 4), l.num(5, 10), l.num(11, 20), l.num(21, 32), l.num(33, 44),
                l.alpha(45, 54), l.alpha(55, 73), l.alpha(80, 87), l.num(88, 94));
    }

    private FileControl fileControl(Line l) {
        return new FileControl(l.num(2, 7), l.num(8, 13), l.num(14, 21), l.num(22, 31), l.num(32, 43),
                l.num(44, 55));
    }

    private char peekType() {
        if (index >= lines.size() || lines.get(index).isEmpty()) {
            return 0;
        }
        return lines.get(index).charAt(0);
    }

    /** Gives a precise error when the next record is well-formed but neither of the allowed types. */
    private void expectOneOf(char a, char b) {
        if (index < lines.size() && lines.get(index).length() == RECORD_LENGTH
                && peekType() != a && peekType() != b) {
            throw new AchParseException(index + 1,
                    "expected " + describe(a) + " or " + describe(b) + ", found type " + peekType());
        }
    }

    private static String describe(char type) {
        return "record type " + type + " (" + RECORD_NAMES.get(type) + ")";
    }

    private Line next(char expectedType) {
        int lineNumber = index + 1;
        String expected = describe(expectedType);
        if (index >= lines.size()) {
            throw new AchParseException(lineNumber, "expected " + expected + " but the file ended");
        }
        String text = lines.get(index);
        if (text.length() != RECORD_LENGTH) {
            throw new AchParseException(lineNumber,
                    "record is " + text.length() + " characters, expected " + RECORD_LENGTH);
        }
        if (text.charAt(0) != expectedType) {
            throw new AchParseException(lineNumber, "expected " + expected + ", found type " + text.charAt(0));
        }
        index++;
        return new Line(text, lineNumber);
    }

    /** One record, with field accessors using 1-based inclusive positions as in the Nacha spec. */
    private record Line(String text, int lineNumber) {

        String alpha(int from, int to) {
            return text.substring(from - 1, to).trim();
        }

        long num(int from, int to) {
            String field = text.substring(from - 1, to);
            if (!field.chars().allMatch(c -> c >= '0' && c <= '9')) {
                throw new AchParseException(lineNumber,
                        "positions " + from + "-" + to + " must be numeric, found '" + field + "'");
            }
            return Long.parseLong(field);
        }
    }
}
