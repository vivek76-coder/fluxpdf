package com.editing.fluxpdf;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.editing.fluxpdf.databinding.ActivityPdfViewerBinding;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.InputStream;

/**
 * Activity for viewing PDF documents page by page.
 * Renders PDF pages as bitmaps using PDFBox-Android's PDFRenderer.
 * Supports opening from within the app and from external apps (file manager, browser, etc.)
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

        // Get PDF URI - check multiple sources
        Uri pdfUri = resolveUri();
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

        // Page jump: tap on page info text to jump to a specific page
        binding.tvPageInfo.setOnClickListener(v -> showPageJumpDialog());

        loadPdf(pdfUri);
    }

    /**
     * Resolves the PDF URI from the intent.
     * Handles: internal EXTRA_PDF_URI, external VIEW action (getData), and SEND action (getParcelableExtra EXTRA_STREAM).
     */
    private Uri resolveUri() {
        Intent intent = getIntent();
        if (intent == null) return null;

        // 1. Internal launch via EXTRA_PDF_URI
        Uri uri = intent.getParcelableExtra(EXTRA_PDF_URI);
        if (uri != null) return uri;

        // 2. External VIEW action (e.g. file manager opens a PDF)
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return intent.getData();
        }

        // 3. External SEND action (e.g. share a PDF to FluxPDF)
        if (Intent.ACTION_SEND.equals(action)) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }

        return null;
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

    private void jumpToPage(int pageNumber) {
        // pageNumber is 1-based from user input
        int zeroBasedPage = pageNumber - 1;
        if (zeroBasedPage >= 0 && zeroBasedPage < totalPages) {
            currentPage = zeroBasedPage;
            updatePageInfo();
            renderCurrentPage();
        } else {
            Toast.makeText(this, "Page must be between 1 and " + totalPages, Toast.LENGTH_SHORT).show();
        }
    }

    private void showPageJumpDialog() {
        if (totalPages <= 1) return;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint("Enter page number (1-" + totalPages + ")");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(currentPage + 1));
        input.selectAll();
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Go to Page")
                .setMessage("Total pages: " + totalPages)
                .setView(layout)
                .setPositiveButton("Go", (dialog, which) -> {
                    try {
                        int page = Integer.parseInt(input.getText().toString().trim());
                        jumpToPage(page);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid page number", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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
