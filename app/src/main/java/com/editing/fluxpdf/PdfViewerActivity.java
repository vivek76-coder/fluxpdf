package com.editing.fluxpdf;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;
import android.view.ViewGroup;
import android.view.LayoutInflater;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.editing.fluxpdf.databinding.ActivityPdfViewerBinding;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity for viewing PDF documents page by page.
 * Renders PDF pages as bitmaps using PDFBox-Android's PDFRenderer.
 * Supports opening from within the app and from external apps (file manager, browser, etc.)
 * Includes a side drawer showing PDF bookmarks/outlines (topics).
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PDF_URI = "extra_pdf_uri";

    private ActivityPdfViewerBinding binding;
    private PDDocument document;
    private PDFRenderer renderer;
    private int currentPage = 0;
    private int totalPages = 0;
    private float zoomLevel = 1.5f; // DPI multiplier: 1.0 = 72dpi, 1.5 = 108dpi, 2.0 = 144dpi

    // Topics / Outlines
    private List<String> topicTitles = new ArrayList<>();
    private List<Integer> topicPages = new ArrayList<>();

    // Track current render to avoid race conditions
    private volatile int renderGeneration = 0;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PDFBoxResourceLoader.init(getApplicationContext());

        binding = ActivityPdfViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup toolbar - toggle drawer on menu icon click
        binding.toolbar.setNavigationOnClickListener(v -> {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                binding.drawerLayout.openDrawer(GravityCompat.START);
            }
        });

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

        // Topics list item click -> jump to that page
        binding.lvTopics.setOnItemClickListener((parent, view, position, id) -> {
            int page = topicPages.get(position);
            if (page >= 0 && page < totalPages) {
                currentPage = page;
                updatePageInfo();
                renderCurrentPage();
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        });

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
                document = PDDocument.load(is, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());
                if (is != null) is.close();

                totalPages = document.getNumberOfPages();
                renderer = new PDFRenderer(document);

                // Extract bookmarks/outlines
                extractOutlines();

                // Calculate default zoom to fit width
                if (totalPages > 0) {
                    PDRectangle mediaBox = document.getPage(0).getMediaBox();
                    float pageWidth = mediaBox.getWidth();
                    if (pageWidth > 0) {
                        int screenWidth = getResources().getDisplayMetrics().widthPixels;
                        // Subtract some padding (e.g., 32px)
                        zoomLevel = (screenWidth - 32) / pageWidth;
                    }
                }

                runOnUiThread(() -> {
                    updatePageInfo();
                    updateZoomText();
                    renderCurrentPage();
                    populateTopicsList();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error loading PDF: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    /**
     * Extracts bookmarks/outlines from the PDF document.
     * If no bookmarks exist, generates a simple per-page list.
     */
    private void extractOutlines() {
        topicTitles.clear();
        topicPages.clear();

        try {
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDDocumentOutline outline = catalog.getDocumentOutline();

            if (outline != null) {
                collectOutlineItems(outline, 0);
            }
        } catch (Exception ignored) {
        }

        // If no bookmarks found, create simple page entries
        if (topicTitles.isEmpty()) {
            for (int i = 0; i < totalPages; i++) {
                topicTitles.add("Page " + (i + 1));
                topicPages.add(i);
            }
        }
    }

    /**
     * Recursively collects outline items (bookmarks) with indentation.
     */
    private void collectOutlineItems(PDOutlineNode node, int depth) {
        try {
            PDOutlineItem current = node.getFirstChild();
            while (current != null) {
                // Create indented title
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < depth; i++) sb.append("    ");
                sb.append(current.getTitle());
                topicTitles.add(sb.toString());

                // Try to resolve page number
                int pageNum = resolveOutlinePage(current);
                topicPages.add(pageNum);

                // Recurse into children
                if (current.getFirstChild() != null) {
                    collectOutlineItems(current, depth + 1);
                }

                current = current.getNextSibling();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Resolves the page number for an outline item.
     */
    private int resolveOutlinePage(PDOutlineItem item) {
        try {
            PDDestination dest = item.getDestination();
            if (dest == null && item.getAction() != null) {
                // Some PDFs use actions instead of destinations
                return 0;
            }
            if (dest instanceof PDPageDestination) {
                PDPageDestination pageDest = (PDPageDestination) dest;
                int pageNum = pageDest.retrievePageNumber();
                if (pageNum >= 0) return pageNum;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private void populateTopicsList() {
        // Custom adapter using item_topic.xml
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, R.layout.item_topic, R.id.tvTopicName, topicTitles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                // Highlight current page topic
                TextView tv = view.findViewById(R.id.tvTopicName);
                if (topicPages.get(position) == currentPage) {
                    tv.setTextColor(0xFF3F51B5); // highlight blue
                } else {
                    tv.setTextColor(0xFF333333); // default dark
                }
                return view;
            }
        };
        binding.lvTopics.setAdapter(adapter);

        // Update topic count
        binding.tvTopicCount.setText(topicTitles.size() + " topics • Tap to jump");
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
        if (newZoom >= 0.5f && newZoom <= 5.0f) {
            zoomLevel = newZoom;
            updateZoomText();
            renderCurrentPage();
        }
    }

    private void updateZoomText() {
        // Show percentage relative to 1.0 scale (72 DPI)
        binding.tvZoomLevel.setText(Math.round(zoomLevel * 100) + "%");
    }

    private void updatePageInfo() {
        binding.tvPageInfo.setText("Page " + (currentPage + 1) + " / " + totalPages);
        binding.btnPrevPage.setEnabled(currentPage > 0);
        binding.btnNextPage.setEnabled(currentPage < totalPages - 1);
    }

    /**
     * Safely calculates the maximum render scale that won't exceed
     * Android's hardware texture limit or cause OOM.
     */
    private float safeRenderScale(float pageW, float pageH, float desiredScale) {
        // Android hardware texture limit is typically 4096px, stay under it
        float maxDim = Math.max(pageW, pageH) * desiredScale;
        if (maxDim > 4096f) {
            desiredScale = 4096f / Math.max(pageW, pageH);
        }
        // Also cap total pixel count to ~16 megapixels to avoid OOM
        float totalPixels = (pageW * desiredScale) * (pageH * desiredScale);
        if (totalPixels > 16_000_000f) {
            desiredScale = (float) Math.sqrt(16_000_000.0 / (pageW * pageH));
        }
        return Math.max(desiredScale, 0.25f);
    }

    private void renderCurrentPage() {
        if (renderer == null) return;

        final int thisGeneration = ++renderGeneration;

        new Thread(() -> {
            try {
                // Abort if a newer render was requested
                if (thisGeneration != renderGeneration) return;

                PDRectangle mediaBox = document.getPage(currentPage).getMediaBox();
                float pageW = mediaBox.getWidth();
                float pageH = mediaBox.getHeight();

                float scale = safeRenderScale(pageW, pageH, zoomLevel);
                Bitmap bitmap = renderer.renderImage(currentPage, scale);

                if (thisGeneration != renderGeneration) {
                    // A newer render was requested while we were working; discard
                    bitmap.recycle();
                    return;
                }

                runOnUiThread(() -> {
                    if (thisGeneration != renderGeneration) return;
                    // Recycle old bitmap
                    if (currentBitmap != null && !currentBitmap.isRecycled()) {
                        currentBitmap.recycle();
                    }
                    currentBitmap = bitmap;
                    binding.ivPdfPage.setImageBitmap(bitmap);
                });
            } catch (Throwable e) {
                if (thisGeneration != renderGeneration) return;
                runOnUiThread(() ->
                        Toast.makeText(this, "Error rendering page: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentBitmap != null && !currentBitmap.isRecycled()) {
            currentBitmap.recycle();
            currentBitmap = null;
        }
        if (document != null) {
            try { document.close(); } catch (Exception ignored) {}
        }
    }
}
