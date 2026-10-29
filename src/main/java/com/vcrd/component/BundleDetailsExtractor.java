package com.vcrd.component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vcrd.dto.ExtractionDtos.BundleDetails;
import com.vcrd.dto.ExtractionDtos.DocumentType;
import com.vcrd.dto.ExtractionDtos.ExtractedLine;
import com.vcrd.dto.ExtractionDtos.FlagSeverity;
import com.vcrd.dto.ExtractionDtos.PageInfo;
import com.vcrd.dto.ExtractionDtos.ReviewFlag;
import com.vcrd.util.AmountInWords;
import com.vcrd.util.GstinValidator;

/**
 * Reads the bundle-level fields of a contractor bill (payment advice, checklist, tracking form, covering
 * letter, payment recommendation, tax invoice, BOQ, cost breakup, PO) and raises review flags.
 *
 * <p>Each page is classified first, so a field is read from the documents that carry it (bank details from
 * the PO, mobile from the tax invoice). Most fields are printed on several documents, and OCR reads each page
 * three times, so every occurrence is collected and the most frequent value wins: an OCR misread rarely
 * repeats identically. Fields that are not found stay null.
 */
final class BundleDetailsExtractor {

    /** Header values already read by {@link InvoiceFieldParser}, used by the checks. */
    record Header(LocalDate invoiceDate, List<String> gstins, BigDecimal base, BigDecimal cgst, BigDecimal sgst,
            BigDecimal igst, BigDecimal total) {
    }

    record Page(int number, DocumentType type, List<String> lines) {
    }

    record Result(BundleDetails details, List<ReviewFlag> flags) {
    }

    private static final Map<DocumentType, Pattern> TITLES = new LinkedHashMap<>();
    static {
        // Most specific first: a covering letter also says "payment recommendation", a PO also says "invoice".
        TITLES.put(DocumentType.BILL_CHECKLIST, ci("check\\s*-?\\s*list\\s*for\\s*(?:running|final)|enclosures\\s*to\\s*be\\s*checked"));
        TITLES.put(DocumentType.BILL_TRACKING_FORM, ci("bill\\s*tracking|signing\\s*authori|bill\\s*received\\s*by"));
        TITLES.put(DocumentType.PAYMENT_ADVICE, ci("payment\\s*advice"));
        TITLES.put(DocumentType.COVERING_LETTER, ci("release\\s*the\\s*payment|payment\\s*recommendation\\s*no"));
        TITLES.put(DocumentType.PURCHASE_ORDER, ci("vendor\\s*code|purchase\\s*order|service\\s*-?\\s*order"));
        TITLES.put(DocumentType.TAX_INVOICE, ci("tax\\s*invoice|invoice\\s*amount\\s*in\\s*words|place\\s*of\\s*supply"));
        TITLES.put(DocumentType.PAYMENT_RECOMMENDATION, ci("certificate\\s*no|certified\\s*on|payment\\s*recommendation"));
        TITLES.put(DocumentType.BILL_OF_QUANTITIES, ci("bill\\s*of\\s*q|\\bboq\\b|measurement\\s*abstract|abstract\\s*of\\s*cost|claim\\s*q"));
        TITLES.put(DocumentType.COST_BREAKUP, ci("cost\\s*break|break\\s*-?\\s*up|total\\s*po\\s*(?:qty|amount)|unit\\s*-?\\s*rate"));
    }

    private static final String DATE = "(\\d{1,2}[./\\-](?:\\d{1,2}|[A-Za-z]{3})[./\\-]\\d{2,4})";
    private static final Pattern MONEY = Pattern.compile("(?<![\\d.])(\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.\\d{2}(?!\\d)");
    private static final Pattern PO_REFERENCE = Pattern.compile("\\b([A-Z]{4})\\s*-\\s*(\\d{10})(?!\\d)");
    private static final Pattern PO_DATE = Pattern.compile("\\d{10}\\D{0,20}?(?i:date|dt)\\s*[:.\\-]?\\s*" + DATE);
    private static final Pattern PINCODE = Pattern.compile("[A-Za-z.]\\s*[-\u2013]\\s*(\\d{6})(?!\\d)");
    private static final Pattern IFSC = Pattern.compile("\\b([A-Z]{4}0[A-Z0-9]{6})\\b");
    private static final Pattern MOBILE = ci("\\b(?:mo|mob|mobile|ph|phone|cell)\\b\\.?\\s*(?:no\\.?)?\\s*[:\\-]*\\s*(?:\\+?91[\\s\\-]?)?([6-9]\\d{9})(?!\\d)");
    private static final Pattern SAC = Pattern.compile("(?<!\\d)(99\\d{4})(?!\\d)");
    private static final Pattern UOM = ci("\\b(LOT|NOS?|SET|EA|EACH|KG|MT|TONS?|HRS?|DAYS?|MONTHS?|SQM|SQFT|RMT|CUM|LS|JOB|UNIT|PCS|LTR)\\b");
    private static final Pattern WORDS_LABEL_CUT = ci("\\s+(?:invoice|inv\\b|certificate|running|date|dated|submitted|certified|"
            + "pan\\b|gstin|wo\\b|po\\b|job\\s*no|no\\s*[:.]|\\d{2}/\\d{2}/\\d{4}).*$");
    private static final Pattern NAME_JUNK = Pattern.compile("[^A-Za-z&.\\s]");

    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("01", "Jammu and Kashmir"), Map.entry("02", "Himachal Pradesh"), Map.entry("03", "Punjab"),
            Map.entry("04", "Chandigarh"), Map.entry("05", "Uttarakhand"), Map.entry("06", "Haryana"),
            Map.entry("07", "Delhi"), Map.entry("08", "Rajasthan"), Map.entry("09", "Uttar Pradesh"),
            Map.entry("10", "Bihar"), Map.entry("11", "Sikkim"), Map.entry("12", "Arunachal Pradesh"),
            Map.entry("13", "Nagaland"), Map.entry("14", "Manipur"), Map.entry("15", "Mizoram"),
            Map.entry("16", "Tripura"), Map.entry("17", "Meghalaya"), Map.entry("18", "Assam"),
            Map.entry("19", "West Bengal"), Map.entry("20", "Jharkhand"), Map.entry("21", "Odisha"),
            Map.entry("22", "Chhattisgarh"), Map.entry("23", "Madhya Pradesh"), Map.entry("24", "Gujarat"),
            Map.entry("26", "Dadra and Nagar Haveli and Daman and Diu"), Map.entry("27", "Maharashtra"),
            Map.entry("29", "Karnataka"), Map.entry("30", "Goa"), Map.entry("31", "Lakshadweep"),
            Map.entry("32", "Kerala"), Map.entry("33", "Tamil Nadu"), Map.entry("34", "Puducherry"),
            Map.entry("35", "Andaman and Nicobar Islands"), Map.entry("36", "Telangana"),
            Map.entry("37", "Andhra Pradesh"), Map.entry("38", "Ladakh"));
    private static final Set<String> GST_RATES = Set.of("0.1", "0.25", "1.5", "3", "5", "12", "18", "28", "40");

    private BundleDetailsExtractor() {
    }

    static DocumentType classify(List<String> lines) {
        String text = String.join("\n", lines);
        for (Map.Entry<DocumentType, Pattern> e : TITLES.entrySet()) {
            if (e.getValue().matcher(text).find()) {
                return e.getKey();
            }
        }
        return DocumentType.OTHER;
    }

    static Result extract(List<Page> pages, Header h, Function<List<String>, List<ExtractedLine>> lineItems) {
        List<String> all = pages.stream().flatMap(p -> p.lines().stream()).toList();
        List<String> invoice = linesOf(pages, DocumentType.TAX_INVOICE);
        List<String> po = linesOf(pages, DocumentType.PURCHASE_ORDER);
        List<String> advice = linesOf(pages, DocumentType.PAYMENT_ADVICE);
        // The covering letter and checklist are written by the QS consultant: its own name, phone and
        // company would otherwise compete with the vendor's and buyer's.
        List<String> partySide = pages.stream()
                .filter(p -> p.type() != DocumentType.COVERING_LETTER && p.type() != DocumentType.BILL_CHECKLIST)
                .flatMap(p -> p.lines().stream()).toList();

        String vendorName = vote(collect(all, List.of(
                ci("(?:sub\\s*-?\\s*contractor|service\\s*provider)\\s*:\\s*(.+)"),
                ci("contractor\\s*/\\s*vendor\\s+(.+)"),
                ci("name\\s*of\\s*(?:the\\s*)?contractor\\W+(.+)"),
                ci("\\bfor\\s+m/s\\.?\\s*(.+?)(?:\\s+for\\b|$)")), BundleDetailsExtractor::personName),
                BundleDetailsExtractor::nameKey);

        // GSTINs: bill-to and ship-to columns of the PO first, then frequency (the vendor's is on most pages).
        String[] poParties = billToShipTo(po);
        String vendorGstin = null;
        String buyerGstin = poParties[0];
        String shipToGstin = poParties[1];
        for (String g : rankGstins(all, h.gstins())) {
            if (g.equals(buyerGstin) || g.equals(shipToGstin)) continue;
            if (vendorGstin == null) {
                vendorGstin = g;
            } else if (buyerGstin == null) {
                buyerGstin = g;
            }
        }

        String buyerName = vote(collect(partySide, List.of(ci(
                "(?:m/s\\.?\\s*)?((?:[A-Za-z]{2,}\\s+){0,2}[A-Za-z]{2,}\\s+(?:private\\s+|pvt\\.?\\s*)?(?:limited|ltd|l[il1]m[a-z]{2,3}d))\\b")),
                BundleDetailsExtractor::companyName), BundleDetailsExtractor::nameKey);
        if (buyerName != null && vendorName != null && nameKey(buyerName).equals(nameKey(vendorName))) {
            buyerName = null;
        }

        String poReference = vote(collectAll(all, PO_REFERENCE, m -> m.group(1) + "-" + m.group(2)), s -> s);
        LocalDate poDate = date(vote(collectAll(all, PO_DATE, m -> m.group(1)), BundleDetailsExtractor::dateKey));

        String projectName = vote(collect(partySide, List.of(
                ci("job\\s*name\\W+(.+)"), ci("^\\W*project(?!\\s*location)\\W+(.+)"), ci("^\\W*client\\W+(.+)"),
                ci("project\\s*name\\s*[:\\-]\\s*(.+)")), BundleDetailsExtractor::projectName),
                BundleDetailsExtractor::nameKey);
        String projectLocation = vote(labelValues(all, ci("project\\s*location\\s*[:\\-]?"),
                BundleDetailsExtractor::place), BundleDetailsExtractor::nameKey);
        String workDescription = vote(collect(all, List.of(
                ci("name\\s*of\\s*(?:the\\s*)?w[a-z]{2,3}\\)?\\W+(.+)"), ci("^\\W*package(?!\\s*details)\\W+(.+)"),
                ci("nature\\s*of\\s*work\\W+(.+)")), BundleDetailsExtractor::phrase), BundleDetailsExtractor::nameKey);

        String runningBillNo = vote(collect(all, List.of(
                ci("running\\s*b[il1][il1][il1]?\\s*no\\.?\\W*(\\d{1,3}\\s*&?\\s*(?:final)?)"),
                ci("certificate\\s*no\\W*(\\d{1,3}\\s*&?\\s*(?:final)?)")), BundleDetailsExtractor::billNo), s -> s);
        String amendment = vote(collect(all, List.of(
                ci("amendment\\s*p\\.?\\s*o\\.?\\s*no\\s*[:.\\-]?\\s*([A-Z0-9/\\-]*\\d[A-Z0-9/\\-]{2,})")), s -> s), s -> s);

        String sacCode = vote(collectAll(all, SAC, m -> m.group(1)), s -> s);
        String uom = uom(all, h.base());

        List<BigDecimal> wordAmounts = new ArrayList<>();
        List<String> wordTexts = new ArrayList<>();
        for (String line : all) {
            Matcher m = AmountInWords.PHRASE.matcher(line);
            if (m.find()) {
                BigDecimal v = AmountInWords.parse(m.group(1));
                if (v != null) {
                    wordAmounts.add(v);
                    wordTexts.add(m.group().strip().replaceFirst("^(?i)upees", "Rupees"));
                }
            }
        }
        String wordsValue = vote(wordAmounts.stream().map(BigDecimal::toPlainString).toList(), s -> s);
        BigDecimal amountInWords = wordsValue == null ? null : new BigDecimal(wordsValue);
        String amountInWordsText = amountInWords == null ? null
                : wordTexts.get(wordAmounts.indexOf(amountInWords));

        BigDecimal deductions = money(vote(collectAll(all, ci("total\\s*deductions?[^\\d]{0,30}?((?:\\d{1,3}(?:,\\d{2,3})*|\\d+)\\.\\d{2})"),
                m -> m.group(1)), s -> s));
        if (deductions == null) {
            // Deduction rows are usually "-" (nil) and OCR misreads "0.00"; the difference between the grand
            // total and the net amount payable, both printed, is reliable.
            BigDecimal gross = lastMoney(all, ci("grand\\s*total"));
            BigDecimal net = lastMoney(all, ci("net\\s*amount\\s*payable"));
            if (gross != null && net != null && gross.compareTo(net) >= 0) deductions = gross.subtract(net);
        }
        LocalDate submitted = date(vote(collectAll(all, ci("submitted\\s*on\\W*" + DATE), m -> m.group(1)), BundleDetailsExtractor::dateKey));
        LocalDate certified = date(vote(collectAll(all, ci("certified\\s*on\\W*" + DATE), m -> m.group(1)), BundleDetailsExtractor::dateKey));

        String vendorMobile = vote(collectAll(invoice.isEmpty() ? partySide : invoice, MOBILE, m -> m.group(1)), s -> s);
        String invoicePincode = pincodeAfter(invoice, firstWord(buyerName));
        boolean invoiceBank = invoice.stream().anyMatch(l -> IFSC.matcher(l.toUpperCase(Locale.ROOT)).find()
                || ci("(?:a/?c|account)\\s*(?:no|number)?\\.?\\s*[:\\-]?\\s*\\d{9,18}").matcher(l).find());

        List<String> poOrAll = po.isEmpty() ? all : po;
        String vendorCode = vote(collectAll(poOrAll, ci("vendor\\s*code\\W*(\\d{5,10})(?!\\d)"), m -> m.group(1)), s -> s);
        String bankAccount = vote(labelValues(poOrAll, ci("(?:account|a/c)\\s*(?:no|number)?\\.?\\s*[:\\-]?"),
                v -> v.matches("\\d{9,18}") ? v : firstMatch(v, "^(\\d{9,18})(?!\\d)")), s -> s);
        String ifsc = vote(collectAll(poOrAll, ci("ifsc\\s*(?:code)?\\W*([A-Z]{4}[0O][A-Z0-9]{6})\\b"),
                m -> m.group(1).toUpperCase(Locale.ROOT).substring(0, 4) + "0" + m.group(1).toUpperCase(Locale.ROOT).substring(5)), s -> s);
        String wbs = voteLongest(collectAll(poOrAll,
                Pattern.compile("(?i:wbs)\\s*(?i:element)?\\s*[:\\-]\\s*([A-Z0-9]{2,}(?:/[A-Z0-9]+){2,})(?![a-z])"),
                m -> m.group(1)));
        LocalDate delivery = deliveryDate(poOrAll, h.base());
        String paymentTerms = vote(collect(poOrAll, List.of(ci("payment\\s*terms?\\s*[:\\-]\\s*(.{3,80})")), String::strip), s -> s);
        String billToPincode = pincodeAfter(po, "bill\\s*to");
        String segment = vote(collectAll(poOrAll, ci("segment\\s*[.:\\-]?\\s*([A-Z&]{2,10})\\b"), m -> m.group(1).toUpperCase(Locale.ROOT)), s -> s);
        String preparedBy = vote(collect(poOrAll, List.of(ci("prepared\\s*b[yv]\\s*[:\\-]?\\s*([A-Za-z][A-Za-z .]{2,40}?)\\s*(?:/|\\d|$)")),
                BundleDetailsExtractor::phrase), BundleDetailsExtractor::nameKey);
        boolean poDraft = poOrAll.stream().anyMatch(l -> ci("draft\\s*(?:purchase|service)[\\s\\-]*order|under\\s*approval").matcher(l).find());

        String jobNo = vote(collectAll(advice.isEmpty() ? all : advice, ci("job\\s*no\\W*([A-Z0-9][A-Z0-9/\\-]{3,})"), m -> m.group(1)), s -> s);
        List<ExtractedLine> costBreakup = lineItems.apply(linesOf(pages, DocumentType.COST_BREAKUP));

        BundleDetails d = new BundleDetails(
                pages.stream().map(p -> new PageInfo(p.number(), p.type())).toList(),
                vendorName, vendorGstin, vendorGstin == null ? null : GstinValidator.pan(vendorGstin), state(vendorGstin),
                buyerName, buyerGstin, buyerGstin == null ? null : GstinValidator.pan(buyerGstin), state(buyerGstin),
                poReference, poDate, projectName, projectLocation, workDescription,
                runningBillNo, runningBillNo != null && runningBillNo.contains("FINAL"), amendment, sacCode, uom,
                amountInWords, amountInWordsText, deductions, submitted, certified,
                vendorMobile, invoicePincode, invoiceBank,
                vendorCode, bankAccount, ifsc, wbs, delivery, paymentTerms, shipToGstin, billToPincode, segment,
                preparedBy, poDraft, jobNo, costBreakup,
                missingSignoffs(linesOf(pages, DocumentType.BILL_TRACKING_FORM)));
        return new Result(d, checks(d, h, pages, advice, wordAmounts));
    }

    // ---------------------------------------------------------------- checks

    private static List<ReviewFlag> checks(BundleDetails d, Header h, List<Page> pages, List<String> advice,
            List<BigDecimal> wordAmounts) {
        List<ReviewFlag> flags = new ArrayList<>();
        boolean hasPo = pages.stream().anyMatch(p -> p.type() == DocumentType.PURCHASE_ORDER);

        if (d.poDraft()) {
            flags.add(fail("PO_NOT_RELEASED", FlagSeverity.HIGH,
                    "PO copy reads 'Draft ... under approval': the PO is not released, so the bill cannot be processed"));
        }
        if (d.finalBill() && d.deliveryDate() != null && h.invoiceDate() != null
                && h.invoiceDate().isBefore(d.deliveryDate())) {
            flags.add(fail("FINAL_BILL_BEFORE_DELIVERY", FlagSeverity.HIGH, "Bill is marked '" + d.runningBillNo()
                    + "' on " + h.invoiceDate() + ", before the PO delivery date " + d.deliveryDate()));
        }
        if (d.poDate() != null && d.poDate().equals(h.invoiceDate())) {
            flags.add(fail("BILL_ON_PO_DATE", FlagSeverity.MEDIUM,
                    "Invoice is dated the same day the PO was created (" + d.poDate() + ")"));
        }
        if (h.total() != null && h.base() != null && h.total().compareTo(h.base()) != 0 && !advice.isEmpty()
                && advice.stream().anyMatch(l -> ci("work\\s*d[o0]ne").matcher(l).find() && containsAmount(l, h.total()))) {
            flags.add(fail("AMOUNT_BASIS_DIFFERS", FlagSeverity.INFO, "Payment Advice shows 'Work done' as "
                    + h.total() + " including tax; base " + h.base()
                    + " and GST are taken from the Tax Invoice / Payment Recommendation"));
        }
        if (d.buyerPincodeOnInvoice() != null && d.billToPincodeOnPo() != null
                && !d.buyerPincodeOnInvoice().equals(d.billToPincodeOnPo())) {
            flags.add(fail("BUYER_ADDRESS_DIFFERS", FlagSeverity.MEDIUM, "Buyer address on the invoice (PIN "
                    + d.buyerPincodeOnInvoice() + ") differs from the PO bill-to address (PIN " + d.billToPincodeOnPo()
                    + ")" + (d.buyerGstin() != null ? " with the same GSTIN " + d.buyerGstin() + "; may be legitimate" : "")));
        }
        if (pages.stream().anyMatch(p -> p.type() == DocumentType.TAX_INVOICE) && !d.invoiceBankDetailsPresent()) {
            flags.add(fail("INVOICE_BANK_DETAILS_BLANK", FlagSeverity.MEDIUM,
                    "Tax invoice has no bank account / IFSC; take payment details from the SAP vendor master"
                            + (d.vendorBankAccount() != null ? " (PO shows A/c " + d.vendorBankAccount()
                                    + (d.vendorIfsc() != null ? ", IFSC " + d.vendorIfsc() : "") + ")" : "")));
        }
        gstChecks(d, h, flags);
        if (h.base() != null && h.total() != null) {
            BigDecimal computed = h.base().add(nz(h.cgst())).add(nz(h.sgst())).add(nz(h.igst()));
            boolean ok = computed.subtract(h.total()).abs().compareTo(InvoiceFieldParser.TOLERANCE) <= 0;
            flags.add(ok ? pass("AMOUNT_MATH", h.base() + " + GST = " + h.total())
                    : fail("AMOUNT_MATH", FlagSeverity.HIGH, "Base + GST = " + computed + " but total is " + h.total()));
        }
        if (h.total() != null && !wordAmounts.isEmpty()) {
            boolean ok = wordAmounts.stream().anyMatch(v -> v.compareTo(h.total()) == 0);
            flags.add(ok ? pass("AMOUNT_IN_WORDS", "Amount in words matches total " + h.total())
                    : fail("AMOUNT_IN_WORDS", FlagSeverity.MEDIUM, "Amount in words (" + d.amountInWords()
                            + ") does not match total " + h.total()));
        }
        if (!d.costBreakup().isEmpty() && h.base() != null) {
            BigDecimal sum = d.costBreakup().stream().map(ExtractedLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean ok = sum.subtract(h.base()).abs().compareTo(InvoiceFieldParser.TOLERANCE) <= 0;
            flags.add(ok ? pass("COST_BREAKUP", d.costBreakup().size() + " cost items add up to the base " + h.base())
                    : fail("COST_BREAKUP", FlagSeverity.MEDIUM, "Cost breakup items add up to " + sum
                            + ", base is " + h.base() + " (some rows may not have been read)"));
        }
        if (pages.size() > 1 && !hasPo) {
            flags.add(fail("PO_COPY_MISSING", FlagSeverity.INFO, "No PO copy found in the bundle"));
        }
        if (!d.missingSignoffs().isEmpty()) {
            flags.add(fail("TRACKING_SIGNOFF_MISSING", FlagSeverity.MEDIUM, "Bill tracking form appears blank for: "
                    + String.join(", ", d.missingSignoffs()) + " (confirm on the document)"));
        }
        if (pages.stream().anyMatch(p -> p.type() == DocumentType.BILL_CHECKLIST)) {
            flags.add(fail("MANUAL_CHECK_CHECKLIST", FlagSeverity.INFO, "Tick boxes on the checklist cannot be read "
                    + "reliably by OCR: check items marked NO (e.g. quality checklist, as-built drawings) manually"));
        }
        return flags;
    }

    private static void gstChecks(BundleDetails d, Header h, List<ReviewFlag> flags) {
        if (d.vendorGstin() == null || d.buyerGstin() == null) {
            return;
        }
        boolean interState = !d.vendorGstin().substring(0, 2).equals(d.buyerGstin().substring(0, 2));
        boolean hasIgst = nz(h.igst()).signum() > 0;
        boolean hasSplit = nz(h.cgst()).signum() > 0 || nz(h.sgst()).signum() > 0;
        String route = d.vendorState() + " (" + d.vendorGstin().substring(0, 2) + ") to " + d.buyerState()
                + " (" + d.buyerGstin().substring(0, 2) + ")";
        if (interState) {
            flags.add(hasIgst && !hasSplit ? pass("GST_TYPE", "Inter-state " + route + ": IGST charged, CGST/SGST nil")
                    : fail("GST_TYPE", FlagSeverity.HIGH, "Inter-state " + route + " must be IGST only"));
        } else {
            boolean equal = nz(h.cgst()).compareTo(nz(h.sgst())) == 0;
            flags.add(hasSplit && !hasIgst && equal ? pass("GST_TYPE", "Intra-state " + route + ": CGST = SGST, IGST nil")
                    : fail("GST_TYPE", FlagSeverity.HIGH, "Intra-state " + route + " must be equal CGST + SGST, no IGST"));
        }
        BigDecimal gst = nz(h.cgst()).add(nz(h.sgst())).add(nz(h.igst()));
        if (h.base() != null && h.base().signum() > 0 && gst.signum() > 0) {
            BigDecimal rate = gst.multiply(BigDecimal.valueOf(100)).divide(h.base(), 2, RoundingMode.HALF_UP).stripTrailingZeros();
            flags.add(GST_RATES.contains(rate.toPlainString()) ? pass("GST_RATE", "GST rate " + rate.toPlainString() + "%")
                    : fail("GST_RATE", FlagSeverity.MEDIUM, "GST works out to " + rate.toPlainString()
                            + "% of base, not a standard rate"));
        }
    }

    // ---------------------------------------------------------------- field helpers

    private static List<String> linesOf(List<Page> pages, DocumentType type) {
        return pages.stream().filter(p -> p.type() == type).flatMap(p -> p.lines().stream()).toList();
    }

    /** First capture group of each pattern on each line, cleaned; blank results dropped. */
    private static List<String> collect(List<String> lines, List<Pattern> patterns, Function<String, String> clean) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            for (Pattern p : patterns) {
                Matcher m = p.matcher(line);
                if (m.find()) {
                    String v = clean.apply(m.group(1));
                    if (v != null && !v.isBlank()) out.add(v);
                }
            }
        }
        return out;
    }

    private static List<String> collectAll(List<String> lines, Pattern p, Function<Matcher, String> value) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m = p.matcher(line);
            while (m.find()) {
                String v = value.apply(m);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** Value after a label on the same line, or on the next line when the label stands alone. */
    private static List<String> labelValues(List<String> lines, Pattern label, Function<String, String> clean) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = label.matcher(lines.get(i));
            if (!m.find()) continue;
            String rest = lines.get(i).substring(m.end()).strip();
            String v = rest.replaceAll("[\\s:\\-_|.;]", "").isEmpty() && i + 1 < lines.size()
                    ? clean.apply(lines.get(i + 1).strip()) : clean.apply(rest);
            if (v != null && !v.isBlank()) out.add(v);
        }
        return out;
    }

    /** Most frequent value by key; ties go to the earliest. Returns the most frequent spelling within the key. */
    private static String vote(List<String> values, Function<String, String> key) {
        Map<String, Map<String, Integer>> groups = new LinkedHashMap<>();
        for (String v : values) {
            String k = key.apply(v);
            if (k == null || k.isEmpty()) continue;
            groups.computeIfAbsent(k, x -> new LinkedHashMap<>()).merge(v, 1, Integer::sum);
        }
        return groups.values().stream()
                .max(Comparator.comparingInt(g -> g.values().stream().mapToInt(Integer::intValue).sum()))
                .flatMap(g -> g.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey))
                .orElse(null);
    }

    /** Like vote, but a longer value that extends a shorter one (EP06/JGRJ/02/CF/01 vs EP06/JGRJ/02) wins. */
    private static String voteLongest(List<String> values) {
        String best = vote(values, s -> s);
        for (String v : values) {
            if (best != null && v.length() > best.length() && v.startsWith(best)) best = v;
        }
        return best;
    }

    /** Ranked by occurrences, then first appearance; OCR misreads corrected by check digit. */
    private static List<String> rankGstins(List<String> lines, List<String> accepted) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            for (String g : gstins(line)) {
                if (accepted.contains(g)) counts.merge(g, 1, Integer::sum);
            }
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
    }

    static List<String> gstins(String line) {
        List<String> out = new ArrayList<>();
        Matcher m = GstinValidator.LOOSE_GSTIN.matcher(line.toUpperCase(Locale.ROOT));
        while (m.find()) {
            String g = GstinValidator.repair(m.group());
            if (g != null && !out.contains(g)) out.add(g);
        }
        return out;
    }

    /** GSTINs of the PO's "Bill To" and "Ship To" columns: the line with two GSTINs below that heading. */
    private static String[] billToShipTo(List<String> po) {
        String billTo = null;
        String shipTo = null;
        for (int i = 0; i < po.size(); i++) {
            String line = po.get(i).toLowerCase(Locale.ROOT);
            if (!(line.contains("bill to") && line.contains("ship to"))) continue;
            for (int j = i + 1; j < Math.min(po.size(), i + 8); j++) {
                List<String> g = gstins(po.get(j));
                if (g.size() >= 2) {
                    billTo = billTo == null ? g.get(0) : billTo;
                    shipTo = shipTo == null ? g.get(1) : shipTo;
                    break;
                }
            }
        }
        return new String[] {billTo, shipTo};
    }

    /** First "City-123456" PIN within five lines after the line matching the anchor. */
    private static String pincodeAfter(List<String> lines, String anchor) {
        if (anchor == null || lines.isEmpty()) return null;
        Pattern a = ci(anchor);
        List<String> found = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!a.matcher(lines.get(i)).find()) continue;
            for (int j = i; j < Math.min(lines.size(), i + 6); j++) {
                Matcher m = PINCODE.matcher(lines.get(j));
                if (m.find()) {
                    found.add(m.group(1));
                    break;
                }
            }
        }
        return vote(found, s -> s);
    }

    private static String uom(List<String> lines, BigDecimal base) {
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            if (base != null && !containsAmount(line, base) && !SAC.matcher(line).find()) continue;
            Matcher m = UOM.matcher(line);
            if (m.find()) found.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return vote(found, s -> s);
    }

    /** "Delivery date" label, else the date printed on the PO line-item row (the "Delivery On / Before" column). */
    private static LocalDate deliveryDate(List<String> lines, BigDecimal base) {
        String labelled = vote(collectAll(lines, ci("deliver(?:y|ed)\\s*(?:date|on|before|by)\\W*" + DATE), m -> m.group(1)),
                BundleDetailsExtractor::dateKey);
        if (labelled != null) return date(labelled);
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            if (base == null || !containsAmount(line, base)) continue;
            Matcher m = Pattern.compile(DATE).matcher(line);
            if (m.find()) found.add(m.group(1));
        }
        return date(vote(found, BundleDetailsExtractor::dateKey));
    }

    private static final Map<String, Pattern> TRACKING_STAGES = new LinkedHashMap<>();
    static {
        TRACKING_STAGES.put("Bill received", ci("bill\\s*received\\s*by\\s*m/s\\.?\\s*\\w+\\s*engineer"));
        TRACKING_STAGES.put("Measurement checked", ci("measurement\\s*checked\\s*by\\s*\\w+"));
        TRACKING_STAGES.put("Bill certified", ci("bill\\s*certified\\s*by\\s*\\w+"));
        TRACKING_STAGES.put("Verified by manager", ci("verified\\s*by\\s*\\w+\\.?\\s*manager"));
        TRACKING_STAGES.put("Site incharge approval", ci("approved\\s*by\\s*\\w+\\.?\\s*site\\s*incharge"));
        TRACKING_STAGES.put("GM approval", ci("approved\\s*by\\s*\\w+\\.?\\s*gm\\b"));
        TRACKING_STAGES.put("Sent to office", ci("bill\\s*sent\\s*to\\s*office"));
    }

    /**
     * A signed row carries handwriting (date, name, signature) after the stage label, which OCR turns into
     * at least a few characters in some pass; a row with nothing after the label in every pass is blank.
     */
    private static List<String> missingSignoffs(List<String> lines) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Pattern> stage : TRACKING_STAGES.entrySet()) {
            boolean seen = false;
            boolean signed = false;
            for (String line : lines) {
                Matcher m = stage.getValue().matcher(line);
                if (m.find()) {
                    seen = true;
                    signed |= line.substring(m.end()).replaceAll("\\s", "").length() >= 2;
                }
            }
            if (seen && !signed) missing.add(stage.getKey());
        }
        return missing;
    }

    private static BigDecimal lastMoney(List<String> lines, Pattern label) {
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

    private static boolean containsAmount(String line, BigDecimal value) {
        Matcher m = MONEY.matcher(line);
        while (m.find()) {
            if (new BigDecimal(m.group().replace(",", "")).compareTo(value) == 0) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- cleaners

    /** A person or firm name: letters only, OCR junk and following labels removed, up to six words. */
    private static String personName(String raw) {
        String v = WORDS_LABEL_CUT.matcher(raw).replaceAll("");
        v = NAME_JUNK.matcher(v).replaceAll(" ").replaceAll("\\s+", " ").strip();
        String[] words = v.split(" ");
        List<String> kept = new ArrayList<>();
        for (String w : words) {
            if (w.replace(".", "").length() >= 2 || "&".equals(w)) kept.add(w);
            if (kept.size() == 6) break;
        }
        return kept.size() >= 2 ? titleCase(String.join(" ", kept)) : null;
    }

    private static String companyName(String raw) {
        String v = raw.replaceAll("(?i)\\bltd\\b\\.?|\\bl[il1]m[a-z]{2,3}d\\b", "Limited")
                .replaceAll("(?i)\\bpvt\\.?", "Private");
        return titleCase(v.replaceAll("\\s+", " ").strip());
    }

    private static String projectName(String raw) {
        Matcher bracket = Pattern.compile("([A-Za-z0-9][^()|]{2,40}\\([^()]{2,30}\\))").matcher(raw);
        String v = bracket.find() ? bracket.group(1) : WORDS_LABEL_CUT.matcher(raw).replaceAll("");
        v = v.replaceAll("[^A-Za-z0-9()&,.\\-\\s]", " ").replaceAll("\\s+", " ").strip();
        return v.length() >= 4 && v.chars().filter(Character::isLetter).count() >= 4 ? v.toUpperCase(Locale.ROOT) : null;
    }

    private static String phrase(String raw) {
        String v = WORDS_LABEL_CUT.matcher(raw).replaceAll("");
        v = v.replaceAll("[^A-Za-z0-9&\\-\\s]", " ").replaceAll("\\s+", " ").strip();
        return v.split(" ").length >= 2 ? v.toUpperCase(Locale.ROOT) : null;
    }

    private static String place(String raw) {
        String v = WORDS_LABEL_CUT.matcher(raw).replaceAll("");
        v = v.replaceAll("\\s+[a-z](?:\\s|$).*$", "").replaceAll("[^A-Za-z,\\s]", " ").replaceAll("\\s+", " ").strip();
        v = v.replaceAll("\\s*,\\s*", ", ");
        return v.length() >= 3 ? titleCase(v) : null;
    }

    private static String billNo(String raw) {
        String v = raw.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        return v.replaceAll("\\s*&\\s*", " & ").replaceAll("(\\d)\\s+FINAL", "$1 & FINAL");
    }

    /** Comparison key tolerant to OCR: letters/digits only, confusable characters folded. */
    private static String nameKey(String s) {
        return s.toUpperCase(Locale.ROOT).replaceAll("PRIVATE|\\bPVT\\b", "").replaceAll("[^A-Z0-9]", "")
                .replace('L', 'I').replace('1', 'I').replace('0', 'O').replace("VV", "W");
    }

    private static String dateKey(String s) {
        LocalDate d = date(s);
        return d == null ? null : d.toString();
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            boolean acronym = w.length() <= 4 && w.equals(w.toUpperCase(Locale.ROOT)) && !w.matches("(?i)ltd|and|of|the");
            sb.append(acronym && w.length() <= 3 ? w
                    : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String firstWord(String name) {
        return name == null ? null : Pattern.quote(name.split(" ")[0]);
    }

    private static String firstMatch(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String state(String gstin) {
        return gstin == null ? null : STATES.get(gstin.substring(0, 2));
    }

    private static LocalDate date(String s) {
        return s == null ? null : InvoiceFieldParser.parseDate(s.replace('-', '/').replace('.', '/'));
    }

    private static BigDecimal money(String s) {
        return s == null ? null : new BigDecimal(s.replace(",", ""));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static ReviewFlag pass(String code, String message) {
        return new ReviewFlag(code, FlagSeverity.INFO, true, message);
    }

    private static ReviewFlag fail(String code, FlagSeverity severity, String message) {
        return new ReviewFlag(code, severity, false, message);
    }

    private static Pattern ci(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }
}
