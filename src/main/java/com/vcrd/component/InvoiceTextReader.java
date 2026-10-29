package com.vcrd.component;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.vcrd.dto.ExtractionDtos.TextSource;
import com.vcrd.exception.BusinessException;
import com.vcrd.util.ScanCleaner;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

/**
 * Gets plain text out of an invoice file. Digital PDFs are read directly with PDFBox (exact);
 * scanned PDFs and images fall back to Tesseract OCR (approximate).
 */
@Component
public class InvoiceTextReader {

    private static final Logger log = LoggerFactory.getLogger(InvoiceTextReader.class);

    /** Below this many non-blank characters a PDF is treated as scanned. */
    private static final int MIN_TEXT_CHARS = 50;
    private static final int OCR_DPI = 300;
    private static final int SINGLE_BLOCK = 6;
    private static final int SPARSE_TEXT = 11;
    /** Separates pages in the returned text, so the parser can tell the documents of a bundle apart. */
    public static final String PAGE_BREAK = "\n\f\n";
    /** A page with fewer recognisable words than this is retried turned sideways. */
    private static final int MIN_WORDS = 25;
    private static final Pattern WORD = Pattern.compile("(?i)\\b[a-z]{4,}\\b");

    private final String tessdataPath;
    private final String language;

    public InvoiceTextReader(@Value("${vcrd.ocr.tessdata-path:}") String tessdataPath,
            @Value("${vcrd.ocr.language:eng}") String language) {
        this.tessdataPath = tessdataPath;
        this.language = language;
    }

    public record ReadText(String text, TextSource source) {
    }

    public ReadText read(byte[] content, String contentType) {
        try {
            if ("application/pdf".equals(contentType)) {
                try (PDDocument pdf = Loader.loadPDF(content)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setPageEnd(PAGE_BREAK);
                    String text = stripper.getText(pdf);
                    if (text.replaceAll("\\s", "").length() >= MIN_TEXT_CHARS) {
                        return new ReadText(text, TextSource.PDF_TEXT);
                    }
                    return new ReadText(ocrPdf(pdf), TextSource.OCR);
                }
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new BusinessException("UNREADABLE_DOCUMENT", "Could not decode image");
            }
            return new ReadText(ocr(image), TextSource.OCR);
        } catch (IOException e) {
            throw new BusinessException("UNREADABLE_DOCUMENT", "Could not read document: " + e.getMessage());
        }
    }

    private String ocrPdf(PDDocument pdf) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdf);
        StringBuilder sb = new StringBuilder();
        for (int page = 0; page < pdf.getNumberOfPages(); page++) {
            sb.append(ocr(renderer.renderImageWithDPI(page, OCR_DPI, ImageType.GRAY))).append(PAGE_BREAK);
        }
        return sb.toString();
    }

    private String ocr(BufferedImage image) {
        if (tessdataPath.isBlank()) {
            throw new BusinessException("OCR_NOT_CONFIGURED",
                    "Document has no text layer and OCR is not configured (set vcrd.ocr.tessdata-path)");
        }
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessdataPath);
        tesseract.setLanguage(language);
        try {
            // Three passes, all kept (the parser takes the first labelled match and uses the rest as evidence):
            // 1. original page, automatic layout: best for running text and letters;
            // 2. cleaned page (black/white, table rules erased), one text block per line: reads ruled tables
            //    with shaded cells that pass 1 turns into noise;
            // 3. cleaned page, sparse text: recovers isolated cells such as an invoice number under its label.
            String original = tesseract.doOCR(image);
            // Scanned bundles often contain landscape sheets (BOQ, cost breakup) scanned sideways.
            // A turned page wins only when it reads clearly more words (OCR of a sideways page is mostly noise).
            if (words(original) < MIN_WORDS) {
                BufferedImage upright = image;
                int best = words(original);
                for (int degrees : new int[] {90, 270}) {
                    BufferedImage turned = rotate(upright, degrees);
                    String text = tesseract.doOCR(turned);
                    if (words(text) >= 10 && words(text) > best * 3 / 2) {
                        best = words(text);
                        image = turned;
                        original = text;
                    }
                }
            }
            BufferedImage cleaned = ScanCleaner.clean(image, Math.max(40, image.getWidth() / 30));
            tesseract.setPageSegMode(SINGLE_BLOCK);
            String table = tesseract.doOCR(cleaned);
            tesseract.setPageSegMode(SPARSE_TEXT);
            return original + "\n" + table + "\n" + tesseract.doOCR(cleaned);
        } catch (TesseractException | UnsatisfiedLinkError e) {
            log.warn("OCR failed", e);
            throw new BusinessException("OCR_FAILED", "OCR failed: " + e.getMessage());
        }
    }

    private static int words(String text) {
        Matcher m = WORD.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private static BufferedImage rotate(BufferedImage image, int degrees) {
        int w = image.getWidth();
        int h = image.getHeight();
        BufferedImage out = new BufferedImage(h, w, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.translate(degrees == 90 ? h : 0, degrees == 90 ? 0 : w);
        g.rotate(Math.toRadians(degrees));
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return out;
    }
}
