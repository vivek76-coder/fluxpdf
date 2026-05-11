package com.editing.fluxpdf;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.editing.fluxpdf.databinding.ActivityMainBinding;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    // Operation constants
    private static final int OP_ADD_PAGE = 1;
    private static final int OP_DELETE_PAGES = 2;
    private static final int OP_REORDER = 3;
    private static final int OP_ROTATE = 4;
    private static final int OP_SPLIT = 5;
    private static final int OP_MERGE = 6;
    private static final int OP_EXTRACT = 7;
    private static final int OP_CROP = 8;
    private static final int OP_VIEW = 9;
    private static final int OP_OCR = 10;
    private static final int OP_COMPRESS = 11;
    private static final int OP_EDIT_TEXT = 12;

    private int currentOperation = 0;
    private Uri inputUri;
    private List<Uri> mergeInputUris;

    // Parameters collected from dialogs
    private List<Integer> collectedPageNumbers;
    private int rotationAngle = 90;
    private int splitAfterPage = 1;
    private float cropTop, cropBottom, cropLeft, cropRight;
    private boolean isSavingSecondHalf = false;
    private int compressionQuality = 1; // 0=Low, 1=Medium, 2=High

    // Activity Result Launchers
    private ActivityResultLauncher<String[]> pickSinglePdfLauncher;
    private ActivityResultLauncher<String[]> pickMultiplePdfsLauncher;
    private ActivityResultLauncher<String> savePdfLauncher;
    private ActivityResultLauncher<String> saveSplitSecondLauncher;
    private ActivityResultLauncher<String[]> pickImageLauncher;
    private Uri collectedImageUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize PDFBox
        PDFBoxResourceLoader.init(getApplicationContext());

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupLaunchers();
        setupClickListeners();
    }

    private void setupLaunchers() {
        // Launcher to pick a single PDF
        pickSinglePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        inputUri = uri;
                        onInputFileSelected();
                    }
                });

        // Launcher to pick multiple PDFs (for merge)
        pickMultiplePdfsLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(),
                uris -> {
                    if (uris != null && uris.size() >= 2) {
                        mergeInputUris = uris;
                        // Go directly to save
                        savePdfLauncher.launch("merged.pdf");
                    } else if (uris != null && uris.size() == 1) {
                        Toast.makeText(this, "Please select at least 2 PDFs to merge", Toast.LENGTH_SHORT).show();
                    }
                });

        // Launcher to save the output PDF
        savePdfLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> {
                    if (uri != null) {
                        performOperation(uri);
                    }
                });

        // Launcher to save the second half of a split
        saveSplitSecondLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"),
                uri -> {
                    if (uri != null) {
                        performSplitSecondHalf(uri);
                    }
                });

        // Launcher to pick an image for OCR
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        collectedImageUri = uri;
                        savePdfLauncher.launch("ocr_result.pdf");
                    }
                });
    }

    private void setupClickListeners() {
        binding.cardAddPage.setOnClickListener(v -> startOperation(OP_ADD_PAGE));
        binding.cardDeletePage.setOnClickListener(v -> startOperation(OP_DELETE_PAGES));
        binding.cardReorder.setOnClickListener(v -> startOperation(OP_REORDER));
        binding.cardRotate.setOnClickListener(v -> startOperation(OP_ROTATE));
        binding.cardSplit.setOnClickListener(v -> startOperation(OP_SPLIT));
        binding.cardMerge.setOnClickListener(v -> startOperation(OP_MERGE));
        binding.cardExtract.setOnClickListener(v -> startOperation(OP_EXTRACT));
        binding.cardCrop.setOnClickListener(v -> startOperation(OP_CROP));
        binding.cardViewPdf.setOnClickListener(v -> startOperation(OP_VIEW));
        binding.cardOcr.setOnClickListener(v -> startOperation(OP_OCR));
        binding.cardCompress.setOnClickListener(v -> startOperation(OP_COMPRESS));
        binding.cardEditText.setOnClickListener(v -> startOperation(OP_EDIT_TEXT));
    }

    private void startOperation(int operation) {
        currentOperation = operation;
        isSavingSecondHalf = false;

        if (operation == OP_MERGE) {
            pickMultiplePdfsLauncher.launch(new String[]{"application/pdf"});
        } else if (operation == OP_OCR) {
            pickImageLauncher.launch(new String[]{"image/*"});
        } else {
            pickSinglePdfLauncher.launch(new String[]{"application/pdf"});
        }
    }

    /**
     * Called after a single input PDF has been selected.
     * Shows the appropriate dialog or goes directly to save.
     */
    private void onInputFileSelected() {
        new Thread(() -> {
            try {
                int pageCount = PdfEditorUtils.getPageCount(this, inputUri);
                String pageInfo = "Total pages: " + pageCount;

                runOnUiThread(() -> {
                    switch (currentOperation) {
                        case OP_ADD_PAGE:
                            // No extra input needed, go to save
                            savePdfLauncher.launch("added_page.pdf");
                            break;
                        case OP_DELETE_PAGES:
                            showPageNumberDialog("Delete Pages", pageInfo, "Pages to delete (e.g. 1,3,5)", () -> {
                                savePdfLauncher.launch("deleted_pages.pdf");
                            });
                            break;
                        case OP_REORDER:
                            showPageNumberDialog("Reorder Pages", pageInfo, "New order (e.g. 3,1,2,4)", () -> {
                                savePdfLauncher.launch("reordered.pdf");
                            });
                            break;
                        case OP_ROTATE:
                            showRotateDialog(pageInfo);
                            break;
                        case OP_SPLIT:
                            showSplitDialog(pageInfo, pageCount);
                            break;
                        case OP_EXTRACT:
                            showPageNumberDialog("Extract Pages", pageInfo, "Pages to extract (e.g. 1,3,5)", () -> {
                                savePdfLauncher.launch("extracted.pdf");
                            });
                            break;
                        case OP_CROP:
                            showCropDialog(pageInfo);
                            break;
                        case OP_VIEW:
                            openPdfViewer();
                            break;
                        case OP_COMPRESS:
                            showCompressDialog();
                            break;
                        case OP_EDIT_TEXT:
                            openPdfTextEditor();
                            break;
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Error reading PDF: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    /**
     * Generic dialog to collect comma-separated page numbers.
     */
    private void showPageNumberDialog(String title, String subtitle, String hint, Runnable onDone) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(subtitle)
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    String text = input.getText().toString().trim();
                    collectedPageNumbers = parsePageNumbers(text);
                    if (collectedPageNumbers.isEmpty()) {
                        Toast.makeText(this, "Please enter valid page numbers", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    onDone.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRotateDialog(String pageInfo) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText pagesInput = new EditText(this);
        pagesInput.setHint("Pages (e.g. 1,3) or blank for all");
        pagesInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(pagesInput);

        EditText angleInput = new EditText(this);
        angleInput.setHint("Angle: 90, 180, or 270");
        angleInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        angleInput.setText("90");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpToPx(8);
        angleInput.setLayoutParams(params);
        layout.addView(angleInput);

        new AlertDialog.Builder(this)
                .setTitle("Rotate Pages")
                .setMessage(pageInfo)
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    String pagesText = pagesInput.getText().toString().trim();
                    collectedPageNumbers = pagesText.isEmpty() ? new ArrayList<>() : parsePageNumbers(pagesText);
                    try {
                        rotationAngle = Integer.parseInt(angleInput.getText().toString().trim());
                        if (rotationAngle != 90 && rotationAngle != 180 && rotationAngle != 270) {
                            Toast.makeText(this, "Angle must be 90, 180, or 270", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid angle", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    savePdfLauncher.launch("rotated.pdf");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showSplitDialog(String pageInfo, int totalPages) {
        if (totalPages <= 1) {
            Toast.makeText(this, "PDF must have at least 2 pages to split", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText input = new EditText(this);
        input.setHint("Split after page number");
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Split PDF")
                .setMessage(pageInfo + "\nEnter the page number to split after.\nTwo files will be created.")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    try {
                        splitAfterPage = Integer.parseInt(input.getText().toString().trim());
                        if (splitAfterPage < 1 || splitAfterPage >= totalPages) {
                            Toast.makeText(this, "Page must be between 1 and " + (totalPages - 1), Toast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid page number", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Save first half
                    savePdfLauncher.launch("split_part1.pdf");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCropDialog(String pageInfo) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(20);
        layout.setPadding(pad, pad, pad, 0);

        EditText pagesInput = new EditText(this);
        pagesInput.setHint("Pages (e.g. 1,3) or blank for all");
        pagesInput.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(pagesInput);

        EditText topInput = createMarginInput("Top margin (points)", layout);
        EditText bottomInput = createMarginInput("Bottom margin (points)", layout);
        EditText leftInput = createMarginInput("Left margin (points)", layout);
        EditText rightInput = createMarginInput("Right margin (points)", layout);

        new AlertDialog.Builder(this)
                .setTitle("Crop Pages")
                .setMessage(pageInfo + "\nEnter margins to trim (in points, 72 points = 1 inch)")
                .setView(layout)
                .setPositiveButton("OK", (dialog, which) -> {
                    String pagesText = pagesInput.getText().toString().trim();
                    collectedPageNumbers = pagesText.isEmpty() ? new ArrayList<>() : parsePageNumbers(pagesText);
                    try {
                        cropTop = parseFloat(topInput);
                        cropBottom = parseFloat(bottomInput);
                        cropLeft = parseFloat(leftInput);
                        cropRight = parseFloat(rightInput);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid margin value", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    savePdfLauncher.launch("cropped.pdf");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText createMarginInput(String hint, LinearLayout parent) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setText("0");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dpToPx(4);
        et.setLayoutParams(params);
        parent.addView(et);
        return et;
    }

    /**
     * Called after the user picks a save location. Performs the actual PDF operation.
     */
    private void performOperation(Uri outputUri) {
        new Thread(() -> {
            try {
                switch (currentOperation) {
                    case OP_ADD_PAGE:
                        PdfEditorUtils.addBlankPage(this, inputUri, outputUri);
                        break;
                    case OP_DELETE_PAGES:
                        PdfEditorUtils.deletePages(this, inputUri, outputUri, collectedPageNumbers);
                        break;
                    case OP_REORDER:
                        PdfEditorUtils.reorderPages(this, inputUri, outputUri, collectedPageNumbers);
                        break;
                    case OP_ROTATE:
                        PdfEditorUtils.rotatePages(this, inputUri, outputUri, collectedPageNumbers, rotationAngle);
                        break;
                    case OP_SPLIT:
                        PdfEditorUtils.splitPdf(this, inputUri, outputUri, splitAfterPage, true);
                        // After saving first half, prompt for second half
                        runOnUiThread(() -> saveSplitSecondLauncher.launch("split_part2.pdf"));
                        return; // Don't show success toast yet
                    case OP_MERGE:
                        PdfEditorUtils.mergePdfs(this, mergeInputUris, outputUri);
                        break;
                    case OP_EXTRACT:
                        PdfEditorUtils.extractPages(this, inputUri, outputUri, collectedPageNumbers);
                        break;
                    case OP_CROP:
                        PdfEditorUtils.cropPages(this, inputUri, outputUri, collectedPageNumbers,
                                cropTop, cropBottom, cropLeft, cropRight);
                        break;
                    case OP_COMPRESS:
                        PdfEditorUtils.compressPdf(this, inputUri, outputUri, compressionQuality);
                        break;
                    case OP_OCR:
                        performOcr(collectedImageUri, outputUri);
                        return; // Toast handled in performOcr
                }
                runOnUiThread(() ->
                        Toast.makeText(this, "✅ Operation completed successfully!", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void performSplitSecondHalf(Uri outputUri) {
        new Thread(() -> {
            try {
                PdfEditorUtils.splitPdf(this, inputUri, outputUri, splitAfterPage, false);
                runOnUiThread(() ->
                        Toast.makeText(this, "✅ Split completed! Both parts saved.", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "❌ Error saving second part: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void openPdfViewer() {
        if (inputUri != null) {
            Intent intent = new Intent(this, PdfViewerActivity.class);
            intent.putExtra(PdfViewerActivity.EXTRA_PDF_URI, inputUri);
            // Grant read permission to the viewer activity
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }

    private void openPdfTextEditor() {
        if (inputUri != null) {
            Intent intent = new Intent(this, PdfTextEditorActivity.class);
            intent.putExtra(PdfTextEditorActivity.EXTRA_PDF_URI, inputUri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        }
    }

    private void performOcr(Uri imageUri, Uri outputUri) {
        try {
            InputImage image = InputImage.fromFilePath(this, imageUri);
            com.google.mlkit.vision.text.TextRecognizer recognizer = 
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

            recognizer.process(image)
                    .addOnSuccessListener(text -> {
                        saveTextToPdf(text.getText(), outputUri);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "OCR Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        } catch (Exception e) {
            Toast.makeText(this, "Error processing image: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveTextToPdf(String text, Uri outputUri) {
        new Thread(() -> {
            try (PDDocument doc = new PDDocument();
                 OutputStream os = getContentResolver().openOutputStream(outputUri)) {
                
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                
                try (PDPageContentStream contentStream = new PDPageContentStream(doc, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(50, 750);
                    
                    // Simple line wrapping
                    String[] lines = text.split("\n");
                    for (String line : lines) {
                        // Limit line length for simplicity
                        if (line.length() > 80) line = line.substring(0, 80);
                        contentStream.showText(line);
                        contentStream.newLineAtOffset(0, -15);
                    }
                    contentStream.endText();
                }
                
                doc.save(os);
                runOnUiThread(() -> Toast.makeText(this, "✅ OCR PDF saved successfully!", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "❌ Error saving OCR PDF: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ---- Helpers ----

    private List<Integer> parsePageNumbers(String text) {
        List<Integer> numbers = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return numbers;
        String[] parts = text.split(",");
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part.trim());
                if (num > 0) numbers.add(num);
            } catch (NumberFormatException ignored) {
            }
        }
        return numbers;
    }

    private float parseFloat(EditText et) throws NumberFormatException {
        String text = et.getText().toString().trim();
        if (text.isEmpty()) return 0f;
        return Float.parseFloat(text);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void showCompressDialog() {
        String[] options = {"Low (Smallest file, lower quality)", "Medium (Balanced)", "High (Best quality, larger file)"};
        new AlertDialog.Builder(this)
                .setTitle("Select Compression Quality")
                .setItems(options, (dialog, which) -> {
                    compressionQuality = which;
                    savePdfLauncher.launch("compressed.pdf");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
