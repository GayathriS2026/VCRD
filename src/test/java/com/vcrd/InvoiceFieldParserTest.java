package com.vcrd;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.vcrd.component.InvoiceFieldParser;
import com.vcrd.dto.ExtractionDtos.ExtractedInvoice;
import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.util.GstinValidator;

class InvoiceFieldParserTest {

    private final InvoiceFieldParser parser = new InvoiceFieldParser();

    @Test
    void parsesInterStateInvoice() {
        String text = """
                TAX INVOICE
                Mohd Ikram Faridi
                GSTIN: 09AAWPF0189M2ZY
                Invoice No: BRK-16            Invoice Date: 03/09/2026
                Buyer GSTIN: 27AAACJ1234F1Z5
                Sl  Description   HSN    Qty   Rate      Amount
                1   Cable tray    7308   100   580.00    58,000.00
                Sub Total                                58,000.00
                IGST @ 18%                               10,440.00
                Grand Total                              68,440.00
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.PDF_TEXT);

        assertThat(x.vendorInvoiceNo()).isEqualTo("BRK-16");
        assertThat(x.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(x.baseAmount()).isEqualByComparingTo("58000.00");
        assertThat(x.igstAmount()).isEqualByComparingTo("10440.00");
        assertThat(x.cgstAmount()).isNull();
        assertThat(x.totalAmount()).isEqualByComparingTo(new BigDecimal("68440.00"));

        // HSN 7308 and serial 1 on the row are ignored; only qty x rate = amount is accepted
        assertThat(x.lines()).hasSize(1);
        assertThat(x.lines().get(0).description()).isEqualTo("Cable tray");
        assertThat(x.lines().get(0).quantity()).isEqualByComparingTo("100");
        assertThat(x.lines().get(0).unitPrice()).isEqualByComparingTo("580.00");
    }

    @Test
    void readsMultipleLineItemsWithIndianNumberFormat() {
        String text = """
                Invoice No: INV-77  Date: 01/10/2026
                1  Solar panel 540W   8541   20 Nos   12,500.00   2,50,000.00
                2  Mounting structure 7308  2 Set   25,000.00   50,000.00
                Taxable Value 3,00,000.00
                IGST 18%  54,000.00
                Grand Total 3,54,000.00
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.PDF_TEXT);

        assertThat(x.lines()).extracting(l -> l.amount().toPlainString())
                .containsExactly("250000.00", "50000.00");
        assertThat(x.warnings()).noneMatch(w -> w.startsWith("Line items total"));
    }

    @Test
    void parsesIntraStateInvoiceAndFlagsBadTotal() {
        String text = """
                Bill No. INV/2026/0042
                Dated 15-Oct-2026
                Taxable Value 1,00,000.00
                CGST 9.00% 9,000.00
                SGST 9.00% 9,000.00
                Total Amount 1,20,000.00
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.PDF_TEXT);

        assertThat(x.vendorInvoiceNo()).isEqualTo("INV/2026/0042");
        assertThat(x.invoiceDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(x.baseAmount()).isEqualByComparingTo("100000.00");
        assertThat(x.cgstAmount()).isEqualByComparingTo("9000.00");
        assertThat(x.sgstAmount()).isEqualByComparingTo("9000.00");
        assertThat(x.warnings()).anyMatch(w -> w.startsWith("Base + GST"));
    }

    /**
     * Shaped like the OCR output of a real scanned contractor bill bundle (advice, tax invoice, PO copy),
     * with synthetic party data. OCR misreads ("1GST", "Tolal Amaunt") are as observed.
     */
    @Test
    void handlesScannedBillBundle() {
        String text = """
                PANNo- |AABCA1234A Running Bill No 01 & FINAL
                Service Tax Reg. No|09AABCA1234A1ZH WO/ PO/ No : ZSER-8000012345 Date : 03.09.2026
                Invoice Date: 03/09/2026
                Total Amaunt Before Tax : 58,000.00
                Add: CGST : -
                Add: 1GST . 10,440.00
                Total Amount After Tax 68,440.00
                To: Order No : ZSER-8000012345
                GSTIN-09AABCA1234A1ZH Phane:-9911341862 old PO No.
                1 . Piling re-casting work 995414 N/A 1.000 LOT 58,000.00 13.09.2026 58000.00
                1 .1 Construction services 995414 1 LOT 58,000.00 58,000.00
                BRK-16
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.OCR);

        assertThat(x.vendorInvoiceNo()).as("running bill no is not the invoice no").isNull();
        assertThat(x.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(x.poReferences()).as("phone number excluded").containsExactly("8000012345");
        assertThat(x.baseAmount()).isEqualByComparingTo("58000.00");
        assertThat(x.igstAmount()).isEqualByComparingTo("10440.00");
        assertThat(x.totalAmount()).isEqualByComparingTo("68440.00");
        assertThat(x.lines()).as("repeated PO line kept once, dates ignored").hasSize(1);
    }

    /** Payment advice page: label and value OCR'd as separate lines, rupee sign read as ¥ or %. */
    @Test
    void readsPaymentAdviceTableCells() {
        String text = """
                I_Inv0|ce Noj_
                BRK-16
                Invoice Date:-
                03/09/2026
                | NET WORK DONE (I - Il) %0.00 ¥ 68,440.00 ¥ 68,440.00
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.OCR);

        assertThat(x.vendorInvoiceNo()).isEqualTo("BRK-16");
        assertThat(x.totalAmount()).isEqualByComparingTo("68440.00");
    }

    /** Tax invoice header as OCR'd: first letter of "Invoice" dropped, "No" read as "Ne", values below labels. */
    @Test
    void readsMisspelledLabelsWithValuesBelow() {
        String text = """
                nvalce Ne:
                BRK-16
                Invoice Date.
                03/09/2026
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.OCR);

        assertThat(x.vendorInvoiceNo()).isEqualTo("BRK-16");
        assertThat(x.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    /** Payment recommendation: base not labelled, derived as total - GST because 58,000.00 is printed. */
    @Test
    void derivesBaseOnlyWhenPrinted() {
        String text = """
                Invoice Np o I BRK-16 L
                B 1.00 Tender Items Workdone | - || 58,000.00 58,000.00
                | 3.00 1GST - 18% . | - 10,440.00 10,440.00 |
                GRANDTOTAL(I+1II-1N) 68,440.00 68,440.00
                """;

        ExtractedInvoice x = parser.parse(text, TextSource.OCR);

        assertThat(x.vendorInvoiceNo()).isEqualTo("BRK-16");
        assertThat(x.baseAmount()).isEqualByComparingTo("58000.00");
        assertThat(x.igstAmount()).isEqualByComparingTo("10440.00");
        assertThat(x.totalAmount()).isEqualByComparingTo("68440.00");

        ExtractedInvoice noEvidence = parser.parse("IGST 10,440.00\nGrand Total 68,440.00\n", TextSource.OCR);
        assertThat(noEvidence.baseAmount()).as("58,000 not printed, so not invented").isNull();
    }

    @Test
    void smallNumbersDoNotFormFakeLineItems() {
        ExtractedInvoice x = parser.parse("I PR TS 1.2 noise 5 more 7\n", TextSource.OCR);
        assertThat(x.lines()).isEmpty();
    }

    @Test
    void dateOnNextLineIsNotTakenAsInvoiceNumber() {
        ExtractedInvoice x = parser.parse("Invoice No:\n03/09/2026\n", TextSource.OCR);
        assertThat(x.vendorInvoiceNo()).isNull();
    }

    @Test
    void rejectsGstinWithWrongCheckDigit() {
        assertThat(GstinValidator.isValid("27AAACJ1234F1Z6")).isFalse();
    }
}
