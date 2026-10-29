package com.vcrd;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.vcrd.component.InvoiceFieldParser;
import com.vcrd.dto.ExtractionDtos.BundleDetails;
import com.vcrd.dto.ExtractionDtos.DocumentType;
import com.vcrd.dto.ExtractionDtos.ExtractedInvoice;
import com.vcrd.dto.ExtractionDtos.PageInfo;
import com.vcrd.dto.ExtractionDtos.ReviewFlag;
import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.util.AmountInWords;
import com.vcrd.util.GstinValidator;

/**
 * Bundle shaped like the OCR output of a real contractor bill (payment advice, tax invoice, PO copy,
 * cost breakup), pages separated by form feeds, OCR misreads as observed.
 */
class BundleDetailsTest {

    private static final String BUNDLE = """
            Payment Advice Cum Certification for Sub-Contractor | Hiring | Service provider
            Name of the Subcontractor | Service Provider: MOHD IKRAM FARIDI
            Name of the work PILING RE-CASTING WORK
            Job Name:- JGRJ SOLAR (RUVNL 100 MW) Invoice Date:- | 03/09/2026
            Invoice No:- | BRK-16
            PAN No:- AAWPF0189M Running Bill No 01 & FINAL
            Service Tax Reg. No|09AAWPF0189M2ZY WO/ PO/ No : ZSER-8000035454 Date : 03.09.2026
            | NET WORK DONE (I - Il) 0.00 ¥ 68,440.00 ¥ 68,440.00
            \f
            TAX INVOICE
            GSTIN:09AAWPF0189M2ZY MO. 7906728572
            Name | M/S.Jakson Green Limited MOHD IKRAM FARIDI Invoice No: BRK-16
            Address | Bikaner,Rajasthan-334001, Address UTTAR PRADESH-243504 India Invoice Date: 03/09/2026
            GSTIN 08AAFCJ6350CLZR PAN NO AAWPFO189M
            1 Piling re-casting work 995414 LOT 1.000 58,000.00 58,000.00 18% 10,440.00 68,440.00
            Taxable Value 58,000.00
            IGST 18% 10,440.00
            Grand Total 68,440.00
            Total Invoice Amount in Words: Rupees Sixty Eight Thousand Four Hundred Forty Only
            Name of Bank : - A/c No : - IFSC : -
            \f
            Draft SERVICE-ORDER
            Jakson Green Limited
            To: order No : ZSER-8000035454
            Vendor Code 40003156 Date : 03.09.2026
            Mohd Ikram Faridi Segment . EPC
            GSTIN-09AAWPF0189M2ZY Phane:-9911341862 old PO No.
            IFSC: SBIN0010872 Account No.: 30756405801
            Bill To: - Ship To: -
            Jakson Green Limited JGRJ Solar Private Limited
            6th Floor , Unit No. 601 , Mall of Jaipur Jaipur KHASRA NO.3
            , Rajasthan-302021, RAJASTHAN, Jodhpur - 342037, RAJASTHAN,
            Phone: Fax: GSTN No-08AAFCJ6350C1ZR, Phone: FAX No:GSTN No- 0BAAGCJO0507N1ZG
            1. Piling re-casting work 995414 N/A 1.000 LOT 58,000.00 13.09.2026 58000.00
            WBS : EP06/JGRJ/02/CF/01
            Payment Terms : Pay immediately
            Prepared By: Shanu Pratap / 03.09.2026
            THIS IS DRAFT PURCHASE ORDER, UNDER APPROVAL.
            \f
            Sr. No. Item Description Unit UnitRate Total PO Qty Total PO Amount
            1 JCB for excavation, soil backfilling, compaction Hrs | 1,200 16 19,200 |
            .2 Dumper of soil for backfill the well Nos 5,000 2 X 10,000 | !
            -3 Tractor trolly for backfill the soil in well Nos 1,500 2 3,000
            4 levelling, fixing of channel and recasting of piling with pile cap, Nos 750 28 | X21,000 |
            5 Water tanker for compaction of soil Nos 800 : 6 %4,800 !
            Total Amount X 58,000 X 58,000 j
            """;

    private final ExtractedInvoice x = new InvoiceFieldParser().parse(BUNDLE, TextSource.OCR);

    @Test
    void classifiesPages() {
        assertThat(x.details().pages()).extracting(PageInfo::type).containsExactly(DocumentType.PAYMENT_ADVICE,
                DocumentType.TAX_INVOICE, DocumentType.PURCHASE_ORDER, DocumentType.COST_BREAKUP);
    }

    @Test
    void readsCommonFields() {
        BundleDetails d = x.details();
        assertThat(x.vendorInvoiceNo()).isEqualTo("BRK-16");
        assertThat(x.invoiceDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(x.baseAmount()).isEqualByComparingTo("58000");
        assertThat(x.igstAmount()).isEqualByComparingTo("10440");
        assertThat(x.totalAmount()).isEqualByComparingTo("68440");
        assertThat(d.vendorName()).isEqualTo("Mohd Ikram Faridi");
        assertThat(d.vendorGstin()).isEqualTo("09AAWPF0189M2ZY");
        assertThat(d.vendorPan()).as("from the GSTIN, not the misread PAN").isEqualTo("AAWPF0189M");
        assertThat(d.buyerName()).isEqualTo("Jakson Green Limited");
        assertThat(d.buyerGstin()).as("CLZR misread corrected by check digit").isEqualTo("08AAFCJ6350C1ZR");
        assertThat(d.buyerState()).isEqualTo("Rajasthan");
        assertThat(d.poReference()).isEqualTo("ZSER-8000035454");
        assertThat(d.poDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(d.projectName()).isEqualTo("JGRJ SOLAR (RUVNL 100 MW)");
        assertThat(d.workDescription()).isEqualTo("PILING RE-CASTING WORK");
        assertThat(d.runningBillNo()).isEqualTo("01 & FINAL");
        assertThat(d.finalBill()).isTrue();
        assertThat(d.sacCode()).isEqualTo("995414");
        assertThat(d.uom()).isEqualTo("LOT");
        assertThat(d.amountInWords()).isEqualByComparingTo("68440");
    }

    @Test
    void readsDocumentSpecificFields() {
        BundleDetails d = x.details();
        assertThat(d.vendorMobile()).isEqualTo("7906728572");
        assertThat(d.invoiceBankDetailsPresent()).isFalse();
        assertThat(d.vendorCode()).isEqualTo("40003156");
        assertThat(d.vendorBankAccount()).isEqualTo("30756405801");
        assertThat(d.vendorIfsc()).isEqualTo("SBIN0010872");
        assertThat(d.wbsElement()).isEqualTo("EP06/JGRJ/02/CF/01");
        assertThat(d.deliveryDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(d.paymentTerms()).isEqualTo("Pay immediately");
        assertThat(d.shipToGstin()).isEqualTo("08AAGCJ0507N1ZG");
        assertThat(d.segment()).isEqualTo("EPC");
        assertThat(d.poDraft()).isTrue();
        assertThat(d.costBreakup()).extracting(l -> l.amount().stripTrailingZeros().toPlainString())
                .containsExactly("19200", "10000", "3000", "21000", "4800");
        assertThat(x.lines()).as("invoice lines from the tax invoice only").hasSize(1);
    }

    @Test
    void raisesRedFlags() {
        assertThat(x.flags()).filteredOn(f -> !f.passed()).extracting(ReviewFlag::code).contains(
                "PO_NOT_RELEASED", "FINAL_BILL_BEFORE_DELIVERY", "BILL_ON_PO_DATE", "AMOUNT_BASIS_DIFFERS",
                "BUYER_ADDRESS_DIFFERS", "INVOICE_BANK_DETAILS_BLANK");
        assertThat(x.flags()).filteredOn(ReviewFlag::passed).extracting(ReviewFlag::code).contains(
                "GST_TYPE", "GST_RATE", "AMOUNT_MATH", "AMOUNT_IN_WORDS", "COST_BREAKUP");
    }

    @Test
    void repairsGstinMisreadsOnlyWhenUnambiguous() {
        assertThat(GstinValidator.repair("0BAAGCJO0507N1ZG")).isEqualTo("08AAGCJ0507N1ZG");
        assertThat(GstinValidator.repair("09AAWPFO189M2ZY")).isEqualTo("09AAWPF0189M2ZY");
        assertThat(GstinValidator.repair("BBAAFCJG3S0CTZR")).as("too many changes is a guess").isNull();
    }

    /** Checklist, tracking form and payment recommendation only, as OCR'd from the real bill. */
    @Test
    void readsRecommendationAndTrackingForm() {
        String text = """
                BILL TRACKING FORM
                1 Bill received by M/s. Jakson Engineer \\\\ i . . ;
                2 Measurement checked by QSSPL ! \\ _$&
                3 Bill certified by QSSPL 03\\ t) 6 Awu/( A'l 004
                4 Verified by JGL Manager & T KurN '
                5 Approved by JGL Site Incharge 6'5\\ '&! | PNV KM.
                I 6 !Approved by JGL. GM
                7 Bill Sent to Office
                \f
                PAYMENT RECOMMENDATION
                Certificate No: 01 & FINAL
                B 1.00 Tender Items Workdone | - || 58,000.00 58,000.00
                | 3.00 1GST - 18% . | - 10,440.00 10,440.00 |
                GRANDTOTAL(I+1II-1N) 68,440.00 68,440.00
                | NET AMOUNT PAYABLE (IV - V) - 68,440.00 68,440.00 |
                Rupees Sixty Eight JRwkand Four Hundred Fortyonly
                """;
        ExtractedInvoice r = new InvoiceFieldParser().parse(text, TextSource.OCR);

        assertThat(r.details().missingSignoffs()).containsExactly("GM approval", "Sent to office");
        assertThat(r.details().deductions()).isEqualByComparingTo("0");
        assertThat(r.lines()).as("serial 1.00 is not a quantity").isEmpty();
        assertThat(r.details().amountInWords()).as("garbled words are not guessed").isNull();
        assertThat(r.flags()).extracting(ReviewFlag::code).contains("TRACKING_SIGNOFF_MISSING")
                .doesNotContain("AMOUNT_IN_WORDS");
    }

    @Test
    void readsAmountInWords() {
        assertThat(AmountInWords.find("Rupees Sixty Eight Thousand Four Hundred Forty Only"))
                .isEqualByComparingTo(new BigDecimal("68440"));
        assertThat(AmountInWords.find("Rupees Three Lakh Fifty Four Thousand and Fifty Paise Only"))
                .isEqualByComparingTo(new BigDecimal("354000.50"));
    }
}
