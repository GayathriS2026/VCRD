package com.vcrd.component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import com.vcrd.exception.BusinessException;

/** Shared file limits and content checks for intake and later uploads. */
@Component
public class InvoiceUploadValidator {
    public static final long MAX_FILE_BYTES = 10 * 1024 * 1024;
    private static final Map<String, String> TYPES = Map.of(
            "application/pdf", "pdf", "image/png", "png", "image/jpeg", "jpg",
            "application/msword", "doc", "application/vnd.ms-excel", "xls",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");

    public record ValidatedFile(byte[] content, String extension) { }

    public ValidatedFile validate(MultipartFile file) {
        if (file.isEmpty()) throw new BusinessException("EMPTY_FILE", "Uploaded file is empty");
        if (file.getSize() > MAX_FILE_BYTES) throw tooLarge();
        String extension = file.getContentType() == null ? null : TYPES.get(file.getContentType());
        if (extension == null) {
            throw new BusinessException("UNSUPPORTED_FILE_TYPE", "Allowed types: PDF, DOC, DOCX, XLS, XLSX, PNG, JPG, JPEG");
        }
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException e) { throw new UncheckedIOException(e); }
        if (bytes.length > MAX_FILE_BYTES) throw tooLarge();
        if (!matches(bytes, extension)) {
            throw new BusinessException("FILE_CONTENT_MISMATCH", "File content does not match its declared type");
        }
        return new ValidatedFile(bytes, extension);
    }

    private static BusinessException tooLarge() {
        return new BusinessException("FILE_TOO_LARGE", "Each document must be at most 10 MiB");
    }

    private static boolean matches(byte[] b, String extension) {
        return switch (extension) {
            case "pdf" -> startsWith(b, new int[]{0x25, 0x50, 0x44, 0x46});
            case "png" -> startsWith(b, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg" -> startsWith(b, new int[]{0xFF, 0xD8, 0xFF});
            case "doc", "xls" -> startsWith(b, new int[]{0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1});
            case "docx" -> officeZip(b, "word/document.xml");
            case "xlsx" -> officeZip(b, "xl/workbook.xml");
            default -> false;
        };
    }

    private static boolean startsWith(byte[] bytes, int[] signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[i] & 0xff) != signature[i]) return false;
        }
        return true;
    }

    private static boolean officeZip(byte[] bytes, String expectedEntry) {
        boolean types = false, document = false;
        long expanded = 0;
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 10000) return false;
                types |= "[Content_Types].xml".equals(entry.getName());
                document |= expectedEntry.equals(entry.getName());
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 100 * 1024 * 1024) return false;
                }
            }
            return types && document;
        } catch (IOException e) { return false; }
    }
}
