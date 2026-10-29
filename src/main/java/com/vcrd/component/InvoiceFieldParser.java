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
    /**
     * "Invoice No" including common OCR misreads ("Invaloe No", "lnvoice No"). "Bill No" is deliberately
     * excluded: on contractor bills it is the running-bill number (e.g. "01 &amp; FINAL"), not the invoice number.
     */
    // Labels only; the value is read after the label on the same line, or from the next line when the label
    // stands alone (table cells are often OCR'd as separate lines). Tolerates misreads such as "I_Inv0|ce Noj_".
    private static final Pattern INVOICE_NO = Pattern.compile(
            "(?i)(?<![a-z])(?:[il1|f]?nv[a-z0-9|]{1,3}[ce]e?|inv)\\.?\\s*(?:n[o0pe][a-z]?|number|num|#)(?![a-z])");
    /** Small vendors title the invoice number "Bill No"; used only as a fallback, never on "Running Bill" lines. */
    private static final Pattern BILL_NO = Pattern.compile("(?i)(?<![a-z])bill\\.?\\s*(?:n[o0]|number)(?![a-z])");
    private static final Pattern TOKEN = Pattern.compile("[A-Z0-9][A-Z0-9/\\-]{1,29}", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTHER_LABEL = Pattern.compile(
            "(?i)po|p\\.o|wo|no|date|dated|order|gstin|pan|bill|amount|invoice|challan|ref");
    private static final Pattern WHOLE_LINE_NUMBER = Pattern.compile("^[\\s_|]*([A-Z0-9][A-Z0-9/\\-]{1,29})[\\s_|]*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RUNNING_BILL = Pattern.compile("(?i)\\brunning\\s*bill");
    /** Invoice-date labels first; generic "date" only as a fallback (bundles carry PO, advice and other dates). */
    private static final List<Pattern> DATE_LABELS = List.of(
            Pattern.compile("(?i)(?<![a-z])(?:[il1|f]?nv[a-z0-9|]{1,3}[ce]e?|inv)\\.?\\s*d[a-z]{2,3}(?![a-z])"),
            Pattern.compile("(?i)(?:bill\\s*date|dated|\\bdate)"));
    private static final Pattern DATE = Pattern.compile(
            "(\\d{1,2}[./\\-](?:\\d{1,2}|[A-Za-z]{3})[./\\-]\\d{2,4})");
    /** SAP PO numbers are 10 digits; on documents often prefixed by an order type, e.g. "ZSER-8000035454". */
    private static final Pattern PO_NUMBER = Pattern.compile("(?<![\\d.,])(\\d{10})(?![\\d.,])");
    /** Phone/mobile numbers are also 10 digits; removed before looking for PO numbers. */
    private static final Pattern PHONE = Pattern.compile(
            // "ph\\w{0,4}" also covers OCR misreads such as "Phane".
            "(?i)\\b(?:ph\\w{0,4}|mob\\w{0,4}|mo|tel|fax|contact)\\b\\.?\\s*[:\\-]*\\s*(?:\\+?91[\\s\\-]?)?\\d[\\d\\s\\-]{8,12}");
    private static final Pattern PO_CONTEXT = Pattern.compile("(?i)\\b(?:p\\.?\\s*o|w\\.?\\s*o|order)\\b|[A-Z]{4}-\\d{10}");

    private static final Pattern BASE = label("taxable\\s*(?:value|amount|amt)|sub\\s*-?\\s*total|base\\s*amount"
            + "|assessable\\s*value|total\\s*\\w*\\s*before\\s*tax|total\\s*net\\s*amount");
    // OCR often reads the leading I of IGST as 1 or l.
    private static final Pattern CGST = label("cgst|central\\s*tax");
    private static final Pattern SGST = label("sgst|utgst|state\\s*tax");
    private static final Pattern IGST = label("[il1]gst|integrated\\s*(?:gst|tax)");
    /** Most specific first; bare "total" last and only on lines that aren't sub/tax totals. */
    private static final List<Pattern> TOTALS = List.of(
            label("grand\\s*total|total\\s*invoice\\s*(?:value|amount)|invoice\\s*(?:total|value)"
                    + "|total\\s*amount\\s*after\\s*tax"),
            label("net\\s*(?:amount\\s*)?payable|amount\\s*payable|total\\s*payable|total\\s*amount(?!\\s*before)"
                    // contractor payment advice / certification sheets
                    + "|net\\s*work\\s*done|certifying\\s*the\\s*bill\\s*for\\s*an\\s*amount"
                    + "|certified\\s*amount|amount\\s*of\\s*(?:inr|rs\\.?)"),
            Pattern.compile("(?i)^(?!.*\\b(?:sub|taxable|tax|qty|quantity|before)\\b).*\\btotal\\b"));

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

        // Summary sheets show total and GST but not the taxable value. Derive it only when the result is
        // itself printed in the document, so a misread total or tax can never produce an invented base.
        if (base == null && total != null && (cgst != null || sgst != null || igst != null)) {
            BigDecimal derived = total.subtract(nz(cgst)).subtract(nz(sgst)).subtract(nz(igst));
            if (derived.signum() > 0 && amountPrinted(lines, derived)) {
                base = derived;
                warnings.add("Base amount derived as total - GST (" + derived + ") and found in the document");
            }
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
            // Bundles repeat lines (PO copy, BOQ, cost breakup), so only a shortfall is suspicious.
            BigDecimal itemsTotal = items.stream().map(ExtractedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (base.subtract(itemsTotal).compareTo(TOLERANCE) > 0) {
                warnings.add("Line items total " + itemsTotal + " is less than base amount " + base
                        + "; some rows may not have been read");
            }
        }
        List<String> poReferences = findPoReferences(lines);
        if (source == TextSource.OCR) warnings.add("Read by OCR; verify values against the document");

        return new ExtractedInvoice(source, invoiceNo, date, gstins, poReferences, base, cgst, sgst, igst, total,
                items, warnings);
    }

    /** 10-digit numbers on lines that mention a PO/WO/order, in document order, without duplicates. */
    private static List<String> findPoReferences(List<String> lines) {
        Set<String> found = new LinkedHashSet<>();
        for (String line : lines) {
            if (PO_CONTEXT.matcher(line).find()) {
                String cleaned = PHONE.matcher(line).replaceAll(" ").replaceAll("[A-Z]{4}-(?=\\d{10})", " ");
                Matcher m = PO_NUMBER.matcher(cleaned);
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * A row is a line item when three of its numbers satisfy quantity x rate = amount (within rounding),
     * with the amount being the last of the three. HSN codes, serial numbers and tax rates on the row
     * cannot satisfy that equation by chance in practice, so they are ignored.
     */
    private static List<ExtractedLine> findLineItems(List<String> lines) {
        List<ExtractedLine> items = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String raw : lines) {
            if (SUMMARY_ROW.matcher(raw).find()) {
                continue;
            }
            // Dates such as 03.09.2026 would otherwise be split into numbers that happen to multiply.
            String line = DATE.matcher(raw).replaceAll(" ");
            Matcher m = NUMBER.matcher(line);
            List<BigDecimal> numbers = new ArrayList<>();
            while (m.find()) {
                numbers.add(new BigDecimal(m.group().replace(",", "")));
            }
            ExtractedLine item = matchRow(line, numbers);
            // The same line repeated elsewhere in a bundle (PO copy, BOQ) is kept once.
            if (item != null && seen.add(item.quantity().stripTrailingZeros().toPlainString() + "x"
                    + item.unitPrice().stripTrailingZeros().toPlainString())) {
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
                    if (qty.signum() > 0 && qty.multiply(rate).subtract(amount).abs().compareTo(rowTolerance(amount)) <= 0) {
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
        String invoiceNo = findNumber(lines, INVOICE_NO);
        if (invoiceNo != null) {
            return invoiceNo;
        }
        List<String> billLines = lines.stream().filter(l -> !RUNNING_BILL.matcher(l).find()).toList();
        String billNo = findNumber(billLines, BILL_NO);
        return billNo != null && billNo.length() >= 4 ? billNo : null;
    }

    /**
     * Value after the label on the same line; if the label stands alone, the next line when that line is a
     * single number-like token. Candidates must contain a digit and must not be a date.
     */
    private static String findNumber(List<String> lines, Pattern label) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = label.matcher(lines.get(i));
            while (m.find()) {
                String sameLine = valueAfterLabel(lines.get(i).substring(m.end()));
                if (sameLine != null) {
                    return sameLine;
                }
                if (i + 1 < lines.size()) {
                    Matcher nextLine = WHOLE_LINE_NUMBER.matcher(lines.get(i + 1));
                    if (nextLine.matches() && isInvoiceNumber(nextLine.group(1))) {
                        return nextLine.group(1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * First number-like token within a few words after the label, skipping single-character OCR noise
     * ("Invoice Np o I BRK-16"). Stops at the next label word so a neighbouring field (PO no, date) is
     * never taken instead.
     */
    private static String valueAfterLabel(String rest) {
        String[] tokens = rest.split("[\\s|:;_()\\[\\]]+");
        int seen = 0;
        for (String raw : tokens) {
            String token = raw.replaceAll("^[\\-.]+|[\\-.,]+$", "");
            if (token.length() <= 1) {
                continue;
            }
            if (OTHER_LABEL.matcher(token).matches() || ++seen > 4) {
                return null;
            }
            if (TOKEN.matcher(token).matches() && isInvoiceNumber(token)) {
                return token;
            }
        }
        return null;
    }

    private static boolean amountPrinted(List<String> lines, BigDecimal value) {
        for (String line : lines) {
            Matcher m = MONEY.matcher(line);
            while (m.find()) {
                if (new BigDecimal(m.group().replace(",", "")).compareTo(value) == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isInvoiceNumber(String candidate) {
        return candidate.chars().anyMatch(Character::isDigit) && !DATE.matcher(candidate).matches();
    }

    private static LocalDate findDate(List<String> lines) {
        for (Pattern labelPattern : DATE_LABELS) {
            LocalDate d = findDate(lines, labelPattern);
            if (d != null) return d;
        }
        return null;
    }

    /** Date after the label on the same line, or on the next line when the label stands alone (table cells). */
    private static LocalDate findDate(List<String> lines, Pattern labelPattern) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher label = labelPattern.matcher(lines.get(i));
            if (label.find()) {
                String rest = lines.get(i).substring(label.end());
                Matcher m = DATE.matcher(rest);
                if (m.find()) {
                    LocalDate d = parseDate(m.group(1));
                    if (d != null) return d;
                } else if (rest.replaceAll("[\\s:\\-_|.;]", "").isEmpty() && i + 1 < lines.size()) {
                    Matcher next = DATE.matcher(lines.get(i + 1).strip());
                    if (next.lookingAt()) {
                        LocalDate d = parseDate(next.group(1));
                        if (d != null) return d;
                    }
                }
            }
        }
        return null;
    }

    /** Rounding allowed between qty x rate and amount: 1 paisa on small rows, up to 1 rupee on large ones. */
    private static BigDecimal rowTolerance(BigDecimal amount) {
        BigDecimal relative = amount.abs().multiply(new BigDecimal("0.0001"));
        return relative.max(new BigDecimal("0.01")).min(TOLERANCE);
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
