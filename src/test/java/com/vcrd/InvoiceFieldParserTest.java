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

    @Test
    void rejectsGstinWithWrongCheckDigit() {
        assertThat(GstinValidator.isValid("27AAACJ1234F1Z6")).isFalse();
    }
}
