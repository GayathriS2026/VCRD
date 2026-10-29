package com.vcrd.component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.vcrd.dto.ExtractionDtos.ExtractedInvoice;
import com.vcrd.dto.ExtractionDtos.ExtractedLine;
import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.util.GstinValidator;

/**
 * Rule-based extraction of header fields from Indian GST invoice text. Works line by line:
 * find a line with a known label, take the last money value on it (the rate, e.g. "9%", is skipped).
 * Anything it cannot find or that fails a sanity check is reported as a warning, never guessed.
 */
@Component
public class InvoiceFieldParser {

    /** Rupee rounding allowed when checking base + taxes = total. */
    public static final BigDecimal TOLERANCE = new BigDecimal("1.00");

    private static final Pattern MONEY = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.\\d{2}(?!\\d|\\s*%)");
    private static final Pattern INVOICE_NO = Pattern.compile(
            "(?i)(?:invoice|inv|bill)\\.?\\s*(?:no|number|num|#)\\.?\\s*[:\\-]?\\s*([A-Z0-9][A-Z0-9/\\-]{0,29})");
    private static final Pattern DATE_LABEL = Pattern.compile("(?i)(?:invoice\\s*date|inv\\.?\\s*date|bill\\s*date|dated|date)");
    private static final Pattern DATE = Pattern.compile(
            "(\\d{1,2}[./\\-](?:\\d{1,2}|[A-Za-z]{3})[./\\-]\\d{2,4})");

    private static final Pattern BASE = label("taxable\\s*(?:value|amount|amt)|sub\\s*-?\\s*total|base\\s*amount|assessable\\s*value");
    private static final Pattern CGST = label("cgst|central\\s*tax");
    private static final Pattern SGST = label("sgst|utgst|state\\s*tax");
    private static final Pattern IGST = label("igst|integrated\\s*tax");
    /** Most specific first; bare "total" last and only on lines that aren't sub/tax totals. */
    private static final List<Pattern> TOTALS = List.of(
            label("grand\\s*total|total\\s*invoice\\s*(?:value|amount)|invoice\\s*(?:total|value)"),
            label("net\\s*payable|amount\\s*payable|total\\s*payable|total\\s*amount"),
            Pattern.compile("(?i)^(?!.*\\b(?:sub|taxable|tax|qty|quantity)\\b).*\\btotal\\b"));

    /** Any number, with optional Indian/Western thousands separators and decimals. */
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d{1,3}(?:,\\d{2,3})+(?:\\.\\d+)?(?![\\w%])"
            + "|(?<![\\w.,])\\d+(?:\\.\\d+)?(?![\\w%,])");
    /** Rows that carry totals or taxes, never line items. */
    private static final Pattern SUMMARY_ROW = label(
            "total|sub\\s*-?\\s*total|cgst|sgst|igst|utgst|tax|round(?:ed)?\\s*off|amount\\s*in\\s*words|balance");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            fmt("d/M/uuuu"), fmt("d-M-uuuu"), fmt("d.M.uuuu"),
            fmt("d/M/uu"), fmt("d-M-uu"), fmt("d.M.uu"),
            fmt("d-MMM-uuuu"), fmt("d/MMM/uuuu"), fmt("d-MMM-uu"));

    public ExtractedInvoice parse(String text, TextSource source) {
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        List<String> warnings = new ArrayList<>();

        String invoiceNo = findInvoiceNo(lines);
        LocalDate date = findDate(lines);
        List<String> gstins = findGstins(text, warnings);
        BigDecimal base = amount(lines, BASE);
        BigDecimal cgst = amount(lines, CGST);
        BigDecimal sgst = amount(lines, SGST);
        BigDecimal igst = amount(lines, IGST);
        BigDecimal total = null;
        for (Pattern p : TOTALS) {
            if ((total = amount(lines, p)) != null) break;
        }

        if (invoiceNo == null) warnings.add("Invoice number not found");
        if (date == null) warnings.add("Invoice date not found");
        if (gstins.isEmpty()) warnings.add("No valid GSTIN found");
        if (base == null) warnings.add("Taxable/base amount not found");
        if (total == null) warnings.add("Total amount not found");
        if (cgst == null && sgst == null && igst == null) warnings.add("No GST amounts found");
        if (cgst != null && sgst != null && cgst.compareTo(sgst) != 0) {
            warnings.add("CGST (" + cgst + ") and SGST (" + sgst + ") differ");
        }
        if (igst != null && igst.signum() > 0 && (nz(cgst).signum() > 0 || nz(sgst).signum() > 0)) {
            warnings.add("Both IGST and CGST/SGST present");
        }
        if (base != null && total != null) {
            BigDecimal computed = base.add(nz(cgst)).add(nz(sgst)).add(nz(igst));
            if (computed.subtract(total).abs().compareTo(TOLERANCE) > 0) {
                warnings.add("Base + GST (" + computed + ") does not equal total (" + total
                        + "); other taxes/charges may be present");
            }
        }
        List<ExtractedLine> items = findLineItems(lines);
        if (items.isEmpty()) {
            warnings.add("No line items could be read (quantity x rate = amount not found on any row)");
        } else if (base != null) {
            BigDecimal itemsTotal = items.stream().map(ExtractedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (itemsTotal.subtract(base).abs().compareTo(TOLERANCE) > 0) {
                warnings.add("Line items total " + itemsTotal + " does not equal base amount " + base
                        + "; some rows may not have been read");
            }
        }
        if (source == TextSource.OCR) warnings.add("Read by OCR; verify values against the document");

        return new ExtractedInvoice(source, invoiceNo, date, gstins, base, cgst, sgst, igst, total, items, warnings);
    }

    /**
     * A row is a line item when three of its numbers satisfy quantity x rate = amount (within rounding),
     * with the amount being the last of the three. HSN codes, serial numbers and tax rates on the row
     * cannot satisfy that equation by chance in practice, so they are ignored.
     */
    private static List<ExtractedLine> findLineItems(List<String> lines) {
        List<ExtractedLine> items = new ArrayList<>();
        for (String line : lines) {
            if (SUMMARY_ROW.matcher(line).find()) {
                continue;
            }
            Matcher m = NUMBER.matcher(line);
            List<BigDecimal> numbers = new ArrayList<>();
            while (m.find()) {
                numbers.add(new BigDecimal(m.group().replace(",", "")));
            }
            ExtractedLine item = matchRow(line, numbers);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private static ExtractedLine matchRow(String line, List<BigDecimal> numbers) {
        for (int k = numbers.size() - 1; k >= 2; k--) {
            BigDecimal amount = numbers.get(k);
            if (amount.signum() <= 0) continue;
            for (int j = k - 1; j >= 1; j--) {
                for (int i = j - 1; i >= 0; i--) {
                    BigDecimal qty = numbers.get(i);
                    BigDecimal rate = numbers.get(j);
                    if (qty.signum() > 0 && qty.multiply(rate).subtract(amount).abs().compareTo(TOLERANCE) <= 0) {
                        return new ExtractedLine(description(line), qty, rate, amount);
                    }
                }
            }
        }
        return null;
    }

    /** Text of the row with numbers removed, e.g. "1 Cable tray 7308 100 ..." -> "Cable tray". */
    private static String description(String line) {
        String text = NUMBER.matcher(line).replaceAll(" ").replaceAll("[^\\p{L}\\s\\-/&().]", " ")
                .replaceAll("\\s+", " ").strip();
        return text.isEmpty() ? null : text;
    }

    private static String findInvoiceNo(List<String> lines) {
        for (String line : lines) {
            Matcher m = INVOICE_NO.matcher(line);
            // Skip "Invoice Date" etc. where the captured token is actually a word.
            while (m.find()) {
                String candidate = m.group(1);
                if (candidate.chars().anyMatch(Character::isDigit)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static LocalDate findDate(List<String> lines) {
        for (String line : lines) {
            Matcher label = DATE_LABEL.matcher(line);
            if (label.find()) {
                Matcher m = DATE.matcher(line.substring(label.end()));
                if (m.find()) {
                    LocalDate d = parseDate(m.group(1));
                    if (d != null) return d;
                }
            }
        }
        return null;
    }

    private static List<String> findGstins(String text, List<String> warnings) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = GstinValidator.GSTIN.matcher(text.toUpperCase(Locale.ROOT));
        while (m.find()) {
            if (GstinValidator.isValid(m.group())) {
                found.add(m.group());
            } else {
                warnings.add("GSTIN " + m.group() + " fails check digit (possible misread)");
            }
        }
        return List.copyOf(found);
    }

    private static BigDecimal amount(List<String> lines, Pattern label) {
        for (String line : lines) {
            Matcher l = label.matcher(line);
            if (!l.find()) continue;
            Matcher m = MONEY.matcher(line.substring(l.end()));
            String last = null;
            while (m.find()) last = m.group();
            if (last != null) return new BigDecimal(last.replace(",", ""));
        }
        return null;
    }

    private static LocalDate parseDate(String s) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, f);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }
        return null;
    }

    private static Pattern label(String alternatives) {
        return Pattern.compile("(?i)\\b(?:" + alternatives + ")\\b");
    }

    private static DateTimeFormatter fmt(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
