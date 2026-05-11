package com.editing.fluxpdf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.editing.fluxpdf.databinding.ActivityTextEditorBinding;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Activity for editing text within PDF documents.
 * Supports: changing text, correcting spelling, font size/color/style,
 * moving text position, deleting text, and adding new text.
 */
public class PdfTextEditorActivity extends AppCompatActivity
        implements TextOverlayView.OnTextElementSelectedListener {

    public static final String EXTRA_PDF_URI = "extra_pdf_uri";

    private ActivityTextEditorBinding binding;
    private PDDocument document;
    private PDFRenderer renderer;
    private Uri sourceUri;
    private int currentPage = 0;
    private int totalPages = 0;
    private float pageWidth, pageHeight;
    private float scaleX, scaleY;
    private float userZoomLevel = 1.0f;
    private android.view.ScaleGestureDetector scaleGestureDetector;

    private ActivityResultLauncher<String> savePdfLauncher;

    // Cache for page text elements (loaded on demand)
    private Map<Integer, List<TextOverlayView.TextElement>> allPagesText = new HashMap<>();

    // Render tracking to prevent race conditions
    private volatile int renderGeneration = 0;
    private Bitmap currentBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        binding = ActivityTextEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupSaveLauncher();
        setupToolbar();
        setupToolButtons();
        setupNavigation();

        scaleGestureDetector = new android.view.ScaleGestureDetector(this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(android.view.ScaleGestureDetector detector) {
                userZoomLevel *= detector.getScaleFactor();
                userZoomLevel = Math.max(0.5f, Math.min(userZoomLevel, 3.0f));
                applyZoom();
                return true;
            }

            @Override
            public void onScaleEnd(android.view.ScaleGestureDetector detector) {
                super.onScaleEnd(detector);
                // Re-render the PDF at the new zoom level for crisp quality
                renderCurrentPage();
            }
        });

        binding.textOverlay.setScaleDetector(scaleGestureDetector);
        binding.textOverlay.setOnTextElementSelectedListener(this);

        sourceUri = resolveUri();
        if (sourceUri == null) {
            Toast.makeText(this, "No PDF file provided", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadPdf(sourceUri);
    }

    private Uri resolveUri() {
        Intent intent = getIntent();
        if (intent == null) return null;
        Uri uri = intent.getParcelableExtra(EXTRA_PDF_URI);
        if (uri != null) return uri;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null)
            return intent.getData();
        return null;
    }

    private void setupSaveLauncher() {
        savePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> {
                    if (uri != null) saveEditedPdf(uri);
                });
    }

    private void setupToolbar() {
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupToolButtons() {
        binding.btnAddText.setOnClickListener(v -> showAddTextDialog());
        binding.btnEditText.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            showEditTextDialog(idx);
        });
        binding.btnDeleteText.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            deleteSelectedText(idx);
        });
        binding.btnFontSize.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            showFontSizeDialog(idx);
        });
        binding.btnFontColor.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            showFontColorDialog(idx);
        });
        binding.btnFontStyle.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            showFontStyleDialog(idx);
        });
        binding.btnMoveText.setOnClickListener(v -> {
            int idx = binding.textOverlay.getSelectedIndex();
            if (idx < 0) { toast("Tap on a text element first"); return; }
            boolean drag = !binding.textOverlay.isDragEnabled();
            binding.textOverlay.setDragEnabled(drag);
            binding.btnMoveText.setText(drag ? "Moving…" : "Move");
            toast(drag ? "Drag the selected text to move it" : "Move mode off");
        });
        binding.btnSave.setOnClickListener(v -> {
            storeCurrentPageText();
            savePdfLauncher.launch("edited.pdf");
        });
    }

    private void setupNavigation() {
        binding.btnPrevPage.setOnClickListener(v -> navigatePage(-1));
        binding.btnNextPage.setOnClickListener(v -> navigatePage(1));
    }

    // ---- PDF Loading ----

    private void loadPdf(Uri uri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                document = PDDocument.load(is, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly());
                if (is != null) is.close();
                totalPages = document.getNumberOfPages();
                renderer = new PDFRenderer(document);

                // Don't extract everything upfront - do it per page
                allPagesText.clear();

                runOnUiThread(() -> {
                    updatePageInfo();
                    renderCurrentPage();
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    toast("Error loading PDF: " + e.getMessage());
                    finish();
                });
            }
        }).start();
    }

    /**
     * Extracts text positions from a single PDF page using PDFTextStripper.
     */
    private List<TextOverlayView.TextElement> extractTextElements(int pageIndex) throws IOException {
        List<TextOverlayView.TextElement> elements = new ArrayList<>();

        PDFTextStripper stripper = new PDFTextStripper() {
            String currentLine = "";
            float lineX = -1, lineY = -1;
            float lineFontSize = 12;

            @Override
            protected void writeString(String text, List<TextPosition> textPositions) {
                if (textPositions == null || textPositions.isEmpty()) return;

                TextPosition first = textPositions.get(0);
                float x = first.getXDirAdj();
                float y = first.getYDirAdj();
                float fs = first.getFontSizeInPt();

                // Group text by approximate line position
                if (lineX < 0 || Math.abs(y - lineY) > fs * 0.5f) {
                    // Flush previous line
                    if (!currentLine.isEmpty()) {
                        flushLine(elements);
                    }
                    lineX = x;
                    lineY = y;
                    lineFontSize = fs;
                    currentLine = text;
                } else {
                    currentLine += text;
                }
            }

            private void flushLine(List<TextOverlayView.TextElement> elems) {
                if (currentLine.trim().isEmpty()) return;
                PDPage pg = document.getPage(pageIndex);
                float pgH = pg.getMediaBox().getHeight();
                // Convert from top-left (stripper coords) to bottom-left (PDF coords)
                float pdfY = pgH - lineY;
                TextOverlayView.TextElement elem = new TextOverlayView.TextElement(
                        currentLine.trim(), lineX, pdfY, lineFontSize, Color.BLACK, "normal");
                elems.add(elem);
                currentLine = "";
            }

            @Override
            public String getText(PDDocument doc) throws IOException {
                String result = super.getText(doc);
                // Flush last line
                if (!currentLine.isEmpty()) {
                    flushLine(elements);
                }
                return result;
            }
        };

        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.setSortByPosition(true);
        stripper.getText(document);

        return elements;
    }

    // ---- Rendering ----

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
                if (thisGeneration != renderGeneration) return;

                PDPage page = document.getPage(currentPage);
                PDRectangle mediaBox = page.getMediaBox();
                pageWidth = mediaBox.getWidth();
                pageHeight = mediaBox.getHeight();

                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                float visualScale = (screenWidth - 32f) / pageWidth;

                // Base layout scale should match screen
                scaleX = visualScale;
                scaleY = visualScale;

                // Render at the exact zoom level, capped for safety
                float renderScale = safeRenderScale(pageWidth, pageHeight, visualScale * userZoomLevel);
                Bitmap bitmap = renderer.renderImage(currentPage, renderScale);

                if (thisGeneration != renderGeneration) {
                    bitmap.recycle();
                    return;
                }

                // Load/Extract text elements for this page
                if (!allPagesText.containsKey(currentPage)) {
                    List<TextOverlayView.TextElement> extracted = extractTextElements(currentPage);
                    allPagesText.put(currentPage, extracted);
                }

                runOnUiThread(() -> {
                    if (thisGeneration != renderGeneration) return;

                    // Recycle old bitmap
                    if (currentBitmap != null && !currentBitmap.isRecycled()) {
                        currentBitmap.recycle();
                    }
                    currentBitmap = bitmap;

                    binding.ivPdfPage.setImageBitmap(bitmap);
                    binding.textOverlay.setPageHeight(pageHeight);

                    List<TextOverlayView.TextElement> elems = allPagesText.get(currentPage);
                    binding.textOverlay.setTextElements(new ArrayList<>(elems));

                    applyZoom();
                });
            } catch (Throwable e) {
                if (thisGeneration != renderGeneration) return;
                runOnUiThread(() -> toast("Error rendering: " + e.getMessage()));
            }
        }).start();
    }

    private void applyZoom() {
        if (binding.ivPdfPage.getDrawable() != null && scaleX > 0 && scaleY > 0) {
            int baseWidth = (int) (pageWidth * scaleX);
            int baseHeight = (int) (pageHeight * scaleY);

            int displayWidth = (int) (baseWidth * userZoomLevel);
            int displayHeight = (int) (baseHeight * userZoomLevel);

            binding.ivPdfPage.getLayoutParams().width = displayWidth;
            binding.ivPdfPage.getLayoutParams().height = displayHeight;
            binding.ivPdfPage.requestLayout();

            binding.textOverlay.getLayoutParams().width = displayWidth;
            binding.textOverlay.getLayoutParams().height = displayHeight;
            binding.textOverlay.setScale(scaleX * userZoomLevel, scaleY * userZoomLevel);
            binding.textOverlay.requestLayout();
        }
    }

    private void navigatePage(int direction) {
        storeCurrentPageText();
        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < totalPages) {
            currentPage = newPage;
            userZoomLevel = 1.0f; // Reset zoom on page navigation
            binding.textOverlay.clearSelection();
            binding.textOverlay.setDragEnabled(false);
            binding.btnMoveText.setText("Move");
            updatePageInfo();
            renderCurrentPage();
        }
    }

    private void storeCurrentPageText() {
        allPagesText.put(currentPage, new ArrayList<>(binding.textOverlay.getTextElements()));
    }

    private void updatePageInfo() {
        binding.tvPageInfo.setText("Page " + (currentPage + 1) + " / " + totalPages);
        binding.btnPrevPage.setEnabled(currentPage > 0);
        binding.btnNextPage.setEnabled(currentPage < totalPages - 1);
    }

    // ---- Text Operations ----

    @Override
    public void onTextSelected(TextOverlayView.TextElement element, int index) {
        showEditTextDialog(index);
    }

    @Override
    public void onSelectionCleared() {
        // No action needed
    }

    private void showAddTextDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint("Enter new text");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        layout.addView(input);

        EditText sizeInput = new EditText(this);
        sizeInput.setHint("Font size (default 14)");
        sizeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        sizeInput.setText("14");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(8);
        sizeInput.setLayoutParams(lp);
        layout.addView(sizeInput);

        new AlertDialog.Builder(this)
                .setTitle("Add New Text")
                .setMessage("The text will be placed at center of page. Use Move to reposition.")
                .setView(layout)
                .setPositiveButton("Add", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (text.isEmpty()) { toast("Text cannot be empty"); return; }
                    float fs = 14;
                    try { fs = Float.parseFloat(sizeInput.getText().toString().trim()); }
                    catch (Exception ignored) {}

                    TextOverlayView.TextElement elem = new TextOverlayView.TextElement(
                            text, pageWidth / 4, pageHeight / 2, fs, Color.BLACK, "normal");
                    elem.isNew = true;
                    binding.textOverlay.addTextElement(elem);
                    toast("Text added! Use Move to reposition.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditTextDialog(int index) {
        TextOverlayView.TextElement elem = binding.textOverlay.getTextElements().get(index);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setText(elem.text);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSelectAllOnFocus(true);
        layout.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Edit Text")
                .setMessage("Change the text content (fix spelling, reword, etc.)")
                .setView(layout)
                .setPositiveButton("Apply", (d, w) -> {
                    String newText = input.getText().toString().trim();
                    if (newText.isEmpty()) { toast("Text cannot be empty"); return; }
                    TextOverlayView.TextElement updated = elem.copy();
                    updated.text = newText;
                    updated.isModified = true;
                    binding.textOverlay.updateElement(index, updated);
                    toast("Text updated");
                })
                .setNegativeButton("Cancel", null)
                .create();

        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.postDelayed(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            }, 200);
        });

        dialog.show();
    }

    private void deleteSelectedText(int index) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Text")
                .setMessage("Are you sure you want to delete this text element?")
                .setPositiveButton("Delete", (d, w) -> {
                    binding.textOverlay.removeElement(index);
                    toast("Text deleted");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFontSizeDialog(int index) {
        TextOverlayView.TextElement elem = binding.textOverlay.getTextElements().get(index);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        TextView label = new TextView(this);
        label.setText("Font Size: " + (int) elem.fontSize + " pt");
        label.setTextSize(16);
        layout.addView(label);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(72);
        seekBar.setProgress((int) elem.fontSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(12);
        seekBar.setLayoutParams(lp);
        layout.addView(seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText("Font Size: " + Math.max(progress, 4) + " pt");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        new AlertDialog.Builder(this)
                .setTitle("Change Font Size")
                .setView(layout)
                .setPositiveButton("Apply", (d, w) -> {
                    int newSize = Math.max(seekBar.getProgress(), 4);
                    TextOverlayView.TextElement updated = elem.copy();
                    updated.fontSize = newSize;
                    updated.isModified = true;
                    binding.textOverlay.updateElement(index, updated);
                    toast("Font size changed to " + newSize + "pt");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFontColorDialog(int index) {
        TextOverlayView.TextElement elem = binding.textOverlay.getTextElements().get(index);
        String[] colorNames = {"Black", "Red", "Blue", "Green", "Purple", "Orange", "Dark Gray", "Teal"};
        int[] colorValues = {Color.BLACK, Color.RED, Color.BLUE, 0xFF2E7D32, 0xFF7B1FA2,
                0xFFE65100, Color.DKGRAY, 0xFF00695C};

        new AlertDialog.Builder(this)
                .setTitle("Choose Text Color")
                .setItems(colorNames, (d, which) -> {
                    TextOverlayView.TextElement updated = elem.copy();
                    updated.color = colorValues[which];
                    updated.isModified = true;
                    binding.textOverlay.updateElement(index, updated);
                    toast("Color changed to " + colorNames[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showFontStyleDialog(int index) {
        TextOverlayView.TextElement elem = binding.textOverlay.getTextElements().get(index);
        String[] styles = {"Normal", "Bold", "Italic", "Bold Italic"};
        String[] styleValues = {"normal", "bold", "italic", "bold-italic"};

        new AlertDialog.Builder(this)
                .setTitle("Choose Font Style")
                .setItems(styles, (d, which) -> {
                    TextOverlayView.TextElement updated = elem.copy();
                    updated.fontStyle = styleValues[which];
                    updated.isModified = true;
                    binding.textOverlay.updateElement(index, updated);
                    toast("Style changed to " + styles[which]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Save ----

    private void saveEditedPdf(Uri outputUri) {
        storeCurrentPageText();
        new Thread(() -> {
            try {
                PDDocument newDoc = new PDDocument();

                for (int p = 0; p < totalPages; p++) {
                    PDPage origPage = document.getPage(p);
                    // Import original page to keep images/drawings
                    PDPage newPage = newDoc.importPage(origPage);

                    List<TextOverlayView.TextElement> elems = allPagesText.get(p);
                    if (elems == null) continue; // No changes or extraction for this page

                    // Use APPEND mode to draw over existing content
                    try (PDPageContentStream cs = new PDPageContentStream(newDoc, newPage, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        for (TextOverlayView.TextElement elem : elems) {

                            // 1. If original text was changed/moved/deleted, hide it
                            if (!elem.isNew && (elem.isDeleted || elem.isModified)) {
                                // Draw white rectangle over original position
                                cs.setNonStrokingColor(255, 255, 255);
                                float textW = estimateTextWidth(elem.origText, elem.origFontSize) * 1.1f;
                                float textH = elem.origFontSize * 1.2f;
                                cs.addRect(elem.origX, elem.origY - (textH*0.2f), textW, textH);
                                cs.fill();
                            }

                            // 2. Draw the new/updated text
                            if (!elem.isDeleted && (elem.isNew || elem.isModified)) {
                                PDType1Font font = resolveFont(elem.fontStyle);
                                cs.beginText();
                                cs.setFont(font, elem.fontSize);
                                float r = Color.red(elem.color) / 255f;
                                float g = Color.green(elem.color) / 255f;
                                float b = Color.blue(elem.color) / 255f;
                                cs.setNonStrokingColor(r, g, b);
                                cs.newLineAtOffset(elem.x, elem.y);
                                cs.showText(sanitizeText(elem.text, font));
                                cs.endText();
                            }
                        }
                    }
                }

                try (OutputStream os = getContentResolver().openOutputStream(outputUri)) {
                    newDoc.save(os);
                }
                newDoc.close();
                runOnUiThread(() -> toast("✅ Edited PDF saved successfully!"));
            } catch (Throwable e) {
                runOnUiThread(() -> toast("❌ Error saving: " + e.getMessage()));
            }
        }).start();
    }

    private float estimateTextWidth(String text, float fontSize) {
        // Very rough estimate since we don't have font metrics easily
        if (text == null) return 0;
        return text.length() * fontSize * 0.5f;
    }

    private PDType1Font resolveFont(String style) {
        if (style == null) return PDType1Font.HELVETICA;
        switch (style) {
            case "bold": return PDType1Font.HELVETICA_BOLD;
            case "italic": return PDType1Font.HELVETICA_OBLIQUE;
            case "bold-italic": return PDType1Font.HELVETICA_BOLD_OBLIQUE;
            default: return PDType1Font.HELVETICA;
        }
    }

    private String sanitizeText(String text, PDType1Font font) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            try {
                font.encode(String.valueOf(c));
                sb.append(c);
            } catch (Exception e) {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    // ---- Helpers ----

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
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

    public boolean isDragEnabled() {
        return binding.textOverlay.isDragEnabled();
    }
}
