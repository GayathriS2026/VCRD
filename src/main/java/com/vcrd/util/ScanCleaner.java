package com.vcrd.util;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Prepares scanned pages for OCR. Invoice tables with ruled grid lines and shaded cells are the main cause
 * of garbled OCR: Tesseract reads the rules as characters and the shading as noise. This converts the page
 * to pure black and white (Otsu threshold) and erases long straight dark runs (the table rules), which are
 * far longer than any character stroke.
 */
public final class ScanCleaner {

    private ScanCleaner() {
    }

    /** @param minLineRun shortest dark run, in pixels, treated as a table rule rather than text */
    public static BufferedImage clean(BufferedImage image, int minLineRun) {
        return removeLines(binarize(toGray(image)), minLineRun);
    }

    private static BufferedImage toGray(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_BYTE_GRAY) {
            return image;
        }
        BufferedImage gray = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        gray.getGraphics().drawImage(image, 0, 0, null);
        return gray;
    }

    /** Otsu's method: picks the threshold that best separates ink from background. */
    static BufferedImage binarize(BufferedImage gray) {
        int w = gray.getWidth();
        int h = gray.getHeight();
        Raster in = gray.getRaster();
        int[] histogram = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                histogram[in.getSample(x, y, 0)]++;
            }
        }
        long total = (long) w * h;
        long sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += (long) i * histogram[i];
        }
        long sumBackground = 0;
        long weightBackground = 0;
        double best = 0;
        int threshold = 128;
        for (int i = 0; i < 256; i++) {
            weightBackground += histogram[i];
            if (weightBackground == 0) {
                continue;
            }
            long weightForeground = total - weightBackground;
            if (weightForeground == 0) {
                break;
            }
            sumBackground += (long) i * histogram[i];
            double meanB = (double) sumBackground / weightBackground;
            double meanF = (double) (sum - sumBackground) / weightForeground;
            double between = (double) weightBackground * weightForeground * (meanB - meanF) * (meanB - meanF);
            if (between > best) {
                best = between;
                threshold = i;
            }
        }
        BufferedImage binary = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster out = binary.getRaster();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setSample(x, y, 0, in.getSample(x, y, 0) > threshold ? 1 : 0); // 0 = ink
            }
        }
        return binary;
    }

    /** Whitens horizontal and vertical ink runs of at least minRun pixels. */
    static BufferedImage removeLines(BufferedImage binary, int minRun) {
        int w = binary.getWidth();
        int h = binary.getHeight();
        WritableRaster r = binary.getRaster();
        boolean[] erase = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int start = -1;
            for (int x = 0; x <= w; x++) {
                boolean ink = x < w && r.getSample(x, y, 0) == 0;
                if (ink && start < 0) {
                    start = x;
                } else if (!ink && start >= 0) {
                    if (x - start >= minRun) {
                        for (int k = start; k < x; k++) {
                            erase[y * w + k] = true;
                        }
                    }
                    start = -1;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            int start = -1;
            for (int y = 0; y <= h; y++) {
                boolean ink = y < h && r.getSample(x, y, 0) == 0;
                if (ink && start < 0) {
                    start = y;
                } else if (!ink && start >= 0) {
                    if (y - start >= minRun) {
                        for (int k = start; k < y; k++) {
                            erase[k * w + x] = true;
                        }
                    }
                    start = -1;
                }
            }
        }
        for (int i = 0; i < erase.length; i++) {
            if (erase[i]) {
                r.setSample(i % w, i / w, 0, 1);
            }
        }
        return binary;
    }
}
