package com.editing.fluxpdf;

import android.content.Context;
import android.net.Uri;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class for all PDF editing operations using PdfBox-Android.
 */
public class PdfEditorUtils {

    /**
     * Adds a blank page at the end of the PDF.
     */
    public static void addBlankPage(Context ctx, Uri inputUri, Uri outputUri) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument doc = PDDocument.load(is);
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {
            doc.addPage(new PDPage(PDRectangle.A4));
            doc.save(os);
        }
    }

    /**
     * Deletes specified pages from the PDF.
     * @param pageNumbers 1-based page numbers to delete.
     */
    public static void deletePages(Context ctx, Uri inputUri, Uri outputUri, List<Integer> pageNumbers) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument doc = PDDocument.load(is);
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            // Sort in descending order to avoid index shifting and eliminate duplicates
            List<Integer> sorted = new ArrayList<>(new java.util.HashSet<>(pageNumbers));
            Collections.sort(sorted, Collections.reverseOrder());

            for (int pageNum : sorted) {
                if (pageNum >= 1 && pageNum <= doc.getNumberOfPages()) {
                    doc.removePage(pageNum - 1);
                }
            }

            doc.save(os);
        }
    }

    /**
     * Reorders pages of the PDF according to the given new order.
     * @param newOrder 1-based list specifying the new page order, e.g. [3,1,2] means
     *                 page 3 becomes first, page 1 becomes second, etc.
     */
    public static void reorderPages(Context ctx, Uri inputUri, Uri outputUri, List<Integer> newOrder) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument srcDoc = PDDocument.load(is);
             PDDocument destDoc = new PDDocument();
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            for (int pageNum : newOrder) {
                if (pageNum >= 1 && pageNum <= srcDoc.getNumberOfPages()) {
                    PDPage importedPage = destDoc.importPage(srcDoc.getPage(pageNum - 1));
                    importedPage.setResources(srcDoc.getPage(pageNum - 1).getResources());
                }
            }

            destDoc.save(os);
        }
    }

    /**
     * Rotates specified pages by the given angle.
     * @param pageNumbers 1-based page numbers to rotate. If empty, rotates all pages.
     * @param angle       Rotation angle (90, 180, or 270).
     */
    public static void rotatePages(Context ctx, Uri inputUri, Uri outputUri,
                                   List<Integer> pageNumbers, int angle) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument doc = PDDocument.load(is);
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            if (pageNumbers == null || pageNumbers.isEmpty()) {
                // Rotate all pages
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i);
                    page.setRotation((page.getRotation() + angle) % 360);
                }
            } else {
                for (int pageNum : pageNumbers) {
                    if (pageNum >= 1 && pageNum <= doc.getNumberOfPages()) {
                        PDPage page = doc.getPage(pageNum - 1);
                        page.setRotation((page.getRotation() + angle) % 360);
                    }
                }
            }

            doc.save(os);
        }
    }

    /**
     * Splits a PDF at the specified page, creating a document with pages 1..splitAfterPage.
     * Call this twice with different save targets for both halves.
     *
     * @param splitAfterPage 1-based page number. The output will contain pages 1 to splitAfterPage.
     * @param firstHalf      If true, outputs pages 1..splitAfterPage; if false, outputs splitAfterPage+1..end.
     */
    public static void splitPdf(Context ctx, Uri inputUri, Uri outputUri,
                                int splitAfterPage, boolean firstHalf) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument srcDoc = PDDocument.load(is);
             PDDocument destDoc = new PDDocument();
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            int totalPages = srcDoc.getNumberOfPages();
            int start = firstHalf ? 0 : splitAfterPage;
            int end = firstHalf ? splitAfterPage : totalPages;

            for (int i = start; i < end; i++) {
                destDoc.importPage(srcDoc.getPage(i));
            }

            destDoc.save(os);
        }
    }

    /**
     * Merges multiple PDFs into one.
     */
    public static void mergePdfs(Context ctx, List<Uri> inputUris, Uri outputUri) throws IOException {
        PDFMergerUtility merger = new PDFMergerUtility();
        List<InputStream> streams = new ArrayList<>();

        try (OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {
            for (Uri uri : inputUris) {
                InputStream is = ctx.getContentResolver().openInputStream(uri);
                streams.add(is);
                merger.addSource(is);
            }
            
            merger.setDestinationStream(os);
            merger.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly());
        } finally {
            for (InputStream is : streams) {
                if (is != null) {
                    try { is.close(); } catch (IOException ignored) {}
                }
            }
        }
    }

    /**
     * Extracts specified pages from the PDF into a new document.
     * @param pageNumbers 1-based page numbers to extract.
     */
    public static void extractPages(Context ctx, Uri inputUri, Uri outputUri,
                                    List<Integer> pageNumbers) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument srcDoc = PDDocument.load(is);
             PDDocument destDoc = new PDDocument();
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            for (int pageNum : pageNumbers) {
                if (pageNum >= 1 && pageNum <= srcDoc.getNumberOfPages()) {
                    destDoc.importPage(srcDoc.getPage(pageNum - 1));
                }
            }

            destDoc.save(os);
        }
    }

    /**
     * Crops specified pages by trimming margins.
     * @param pageNumbers 1-based page numbers to crop. If empty, crops all pages.
     * @param top         Points to trim from top.
     * @param bottom      Points to trim from bottom.
     * @param left        Points to trim from left.
     * @param right       Points to trim from right.
     */
    public static void cropPages(Context ctx, Uri inputUri, Uri outputUri,
                                 List<Integer> pageNumbers,
                                 float top, float bottom, float left, float right) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(inputUri);
             PDDocument doc = PDDocument.load(is);
             OutputStream os = ctx.getContentResolver().openOutputStream(outputUri)) {

            List<Integer> targetPages;
            if (pageNumbers == null || pageNumbers.isEmpty()) {
                targetPages = new ArrayList<>();
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    targetPages.add(i);
                }
            } else {
                targetPages = pageNumbers;
            }

            for (int pageNum : targetPages) {
                if (pageNum >= 1 && pageNum <= doc.getNumberOfPages()) {
                    PDPage page = doc.getPage(pageNum - 1);
                    PDRectangle mediaBox = page.getMediaBox();
                    
                    float newWidth = mediaBox.getWidth() - left - right;
                    float newHeight = mediaBox.getHeight() - top - bottom;
                    
                    if (newWidth > 0 && newHeight > 0) {
                        PDRectangle newCropBox = new PDRectangle(
                                mediaBox.getLowerLeftX() + left,
                                mediaBox.getLowerLeftY() + bottom,
                                newWidth,
                                newHeight
                        );
                        page.setCropBox(newCropBox);
                    }
                }
            }

            doc.save(os);
        }
    }

    /**
     * Returns the number of pages in a PDF.
     */
    public static int getPageCount(Context ctx, Uri uri) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             PDDocument doc = PDDocument.load(is)) {
            return doc.getNumberOfPages();
        }
    }
}
