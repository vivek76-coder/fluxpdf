package com.editing.fluxpdf;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.editing.fluxpdf.databinding.ActivityPdfViewerBinding;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.InputStream;

/**
 * Activity for viewing PDF documents page by page.
 * Renders PDF pages as bitmaps using PDFBox-Android's PDFRenderer.
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "extra_pdf_uri";

    private ActivityPdfViewerBinding binding;
    private PDDocument document;
    private PDFRenderer renderer;
    private int currentPage = 0;
    private int totalPages = 0;
    private float zoomLevel = 1.5f; // DPI multiplier: 1.0 = 72dpi, 1.5 = 108dpi, 2.0 = 144dpi

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PDFBoxResourceLoader.init(getApplicationContext());

        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup toolbar back button
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Get PDF URI from intent
        Uri pdfUri = getIntent().getParcelableExtra(EXTRA_PDF_URI);
        if (pdfUri == null) {
            Toast.makeText(this, "No PDF file provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Setup button listeners
        binding.btnPrevPage.setOnClickListener(v -> navigatePage(-1));
        binding.btnNextPage.setOnClickListener(v -> navigatePage(1));
        binding.btnZoomIn.setOnClickListener(v -> zoom(0.25f));
        binding.btnZoomOut.setOnClickListener(v -> zoom(-0.25f));

        loadPdf(pdfUri);
    }

    private void loadPdf(Uri uri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                document = PDDocument.load(is);
                if (is != null) is.close();

                totalPages = document.getNumberOfPages();
                renderer = new PDFRenderer(document);

                runOnUiThread(() -> {
                    updatePageInfo();
                    renderCurrentPage();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    private void navigatePage(int direction) {
        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            currentPage = newPage;
            updatePageInfo();
            renderCurrentPage();
        }
    }

    private void zoom(float delta) {
        float newZoom = zoomLevel + delta;
        if (newZoom >= 0.5f && newZoom <= 4.0f) {
            zoomLevel = newZoom;
            binding.tvZoomLevel.setText(Math.round(zoomLevel * 100 / 1.5f) + "%");
            renderCurrentPage();
        }
    }

    private void updatePageInfo() {
        binding.tvPageInfo.setText("Page " + (currentPage + 1) + " / " + totalPages);
        binding.btnPrevPage.setEnabled(currentPage > 0);
        binding.btnNextPage.setEnabled(currentPage < totalPages - 1);
    }

    private void renderCurrentPage() {
        if (renderer == null) return;

        new Thread(() -> {
            try {
                // Render page at the current zoom level (scale relative to 72 DPI)
                Bitmap bitmap = renderer.renderImage(currentPage, zoomLevel);

                runOnUiThread(() -> {
                    binding.ivPdfPage.setImageBitmap(bitmap);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Error rendering page: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (document != null) {
            try {
                document.close();
            } catch (Exception ignored) {}
        }
    }
}
