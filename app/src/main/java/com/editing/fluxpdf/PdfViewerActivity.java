package com.editing.fluxpdf;

import android.content.Intent;
import android.graphics.Bitmap;
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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

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
    private PdfPagesAdapter pagesAdapter;
    private PagerSnapHelper snapHelper;
    private LinearLayoutManager layoutManager;

    private int currentPage = 0;
    private int totalPages = 0;
    private float zoomLevel = 1.5f; // DPI multiplier
    private boolean isContinuousScroll = true;

    // Topics / Outlines
    private List<String> topicTitles = new ArrayList<>();
    private List<Integer> topicPages = new ArrayList<>();

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
        
        binding.btnScrollMode.setOnClickListener(v -> toggleScrollMode());

        // Page jump: tap on page info text to jump to a specific page
        binding.tvPageInfo.setOnClickListener(v -> showPageJumpDialog());

        // Topics list item click -> jump to that page
        binding.lvTopics.setOnItemClickListener((parent, view, position, id) -> {
            int page = topicPages.get(position);
            if (page >= 0 && page < totalPages) {
                jumpToPage(page + 1);
            }
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        });

        // Setup RecyclerView scrolling listener to update page info
        binding.rvPdfPages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (layoutManager != null) {
                    int firstVisible = layoutManager.findFirstVisibleItemPosition();
                    if (firstVisible != RecyclerView.NO_POSITION && firstVisible != currentPage) {
                        currentPage = firstVisible;
                        updatePageInfo();
                    }
                }
            }
        });

        loadPdf(pdfUri);
    }

    private void toggleScrollMode() {
        isContinuousScroll = !isContinuousScroll;
        setupRecyclerView();
        jumpToPage(currentPage + 1);
    }

    private void setupRecyclerView() {
        if (snapHelper != null) {
            snapHelper.attachToRecyclerView(null);
            snapHelper = null;
        }
        
        if (isContinuousScroll) {
            binding.btnScrollMode.setText("⇅ Scroll");
            binding.pageNavBar.setVisibility(View.GONE);
            layoutManager = new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false);
        } else {
            binding.btnScrollMode.setText("⇹ Page");
            binding.pageNavBar.setVisibility(View.VISIBLE);
            layoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
            snapHelper = new PagerSnapHelper();
            snapHelper.attachToRecyclerView(binding.rvPdfPages);
        }
        binding.rvPdfPages.setLayoutManager(layoutManager);
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
                        zoomLevel = (screenWidth - 32) / pageWidth;
                    }
                }

                runOnUiThread(() -> {
                    pagesAdapter = new PdfPagesAdapter(document, renderer, zoomLevel);
                    setupRecyclerView();
                    binding.rvPdfPages.setAdapter(pagesAdapter);
                    updatePageInfo();
                    updateZoomText();
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
        if (!isContinuousScroll) {
            int newPage = currentPage + direction;
            if (newPage >= 0 && newPage < totalPages) {
                binding.rvPdfPages.smoothScrollToPosition(newPage);
            }
        }
    }

    private void jumpToPage(int pageNumber) {
        // pageNumber is 1-based from user input
        int zeroBasedPage = pageNumber - 1;
        if (zeroBasedPage >= 0 && zeroBasedPage < totalPages) {
            binding.rvPdfPages.scrollToPosition(zeroBasedPage);
            currentPage = zeroBasedPage;
            updatePageInfo();
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
            if (pagesAdapter != null) {
                pagesAdapter.setZoomLevel(zoomLevel);
                // Invalidate views so they re-render at new zoom
                pagesAdapter.notifyDataSetChanged();
                // Ensure layout manager preserves scroll position during dataset change
                layoutManager.scrollToPositionWithOffset(currentPage, 0);
            }
        }
    }

    private void updateZoomText() {
        binding.tvZoomLevel.setText(Math.round(zoomLevel * 100) + "%");
    }

    private void updatePageInfo() {
        binding.tvPageInfo.setText("Page " + (currentPage + 1) + " / " + totalPages);
        binding.btnPrevPage.setEnabled(currentPage > 0);
        binding.btnNextPage.setEnabled(currentPage < totalPages - 1);
    }

    // Rendering is now handled by PdfPagesAdapter

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
        if (pagesAdapter != null) {
            pagesAdapter.shutdown();
        }
        if (document != null) {
            try { document.close(); } catch (Exception ignored) {}
        }
    }
}
