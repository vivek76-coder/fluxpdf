package com.editing.fluxpdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom overlay view that draws text element bounding boxes on top of
 * the rendered PDF page and handles touch-based selection and dragging.
 */
public class TextOverlayView extends View {

    /**
     * Represents a single editable text element on the PDF page.
     */
    public static class TextElement {
        public String text;
        public float x, y;          // PDF coordinates (bottom-left origin)
        public float fontSize;
        public int color;           // Android color int
        public String fontStyle;     // "normal", "bold", "italic", "bold-italic"
        public boolean isNew;        // true if user-added text
        public boolean isDeleted;    // true if original text was removed
        public boolean isModified;   // true if text, font, color or position was changed
        public float origX, origY;   // To hide original text if moved/edited
        public String origText;
        public float origFontSize;
        public int origColor;
        public String origFontStyle;

        // Computed screen coordinates
        public RectF screenRect = new RectF();

        public TextElement(String text, float x, float y, float fontSize, int color, String fontStyle) {
            this.text = text;
            this.origText = text;
            this.x = x;
            this.y = y;
            this.origX = x;
            this.origY = y;
            this.fontSize = fontSize;
            this.origFontSize = fontSize;
            this.color = color;
            this.origColor = color;
            this.fontStyle = fontStyle;
            this.origFontStyle = fontStyle;
            this.isNew = false;
            this.isDeleted = false;
            this.isModified = false;
        }

        public TextElement copy() {
            TextElement copy = new TextElement(text, x, y, fontSize, color, fontStyle);
            copy.isNew = this.isNew;
            copy.isDeleted = this.isDeleted;
            copy.isModified = this.isModified;
            copy.origX = this.origX;
            copy.origY = this.origY;
            copy.origText = this.origText;
            copy.origFontSize = this.origFontSize;
            copy.origColor = this.origColor;
            copy.origFontStyle = this.origFontStyle;
            return copy;
        }
    }

    public interface OnTextElementSelectedListener {
        void onTextSelected(TextElement element, int index);
        void onSelectionCleared();
    }

    private List<TextElement> textElements = new ArrayList<>();
    private int selectedIndex = -1;
    private float scaleX = 1f, scaleY = 1f;
    private float pageHeight = 842f; // default A4 height in points

    private Paint selectionPaint;
    private Paint hoverPaint;
    private Paint textBoundsPaint;
    private Paint handlePaint;
    private Paint textPaint;
    private Paint maskPaint;

    private OnTextElementSelectedListener listener;
    private ScaleGestureDetector scaleDetector;

    // Drag state
    private boolean isDragging = false;
    private float dragStartX, dragStartY;
    private float elemStartX, elemStartY;
    private boolean dragEnabled = false;

    public TextOverlayView(Context context) {
        super(context);
        init();
    }

    public TextOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TextOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionPaint.setColor(0x406C63FF);  // semi-transparent primary
        selectionPaint.setStyle(Paint.Style.FILL);

        hoverPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hoverPaint.setColor(0xFF6C63FF);
        hoverPaint.setStyle(Paint.Style.STROKE);
        hoverPaint.setStrokeWidth(3f);

        textBoundsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textBoundsPaint.setColor(0x00000000);
        textBoundsPaint.setStyle(Paint.Style.STROKE);
        textBoundsPaint.setStrokeWidth(1f);
        textBoundsPaint.setPathEffect(new DashPathEffect(new float[]{8, 4}, 0));

        handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        handlePaint.setColor(0xFF6C63FF);
        handlePaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        
        maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        maskPaint.setColor(Color.WHITE);
        maskPaint.setStyle(Paint.Style.FILL);
    }

    public void setTextElements(List<TextElement> elements) {
        this.textElements = elements;
        selectedIndex = -1;
        invalidate();
    }

    public List<TextElement> getTextElements() {
        return textElements;
    }

    public void setScale(float scaleX, float scaleY) {
        this.scaleX = scaleX;
        this.scaleY = scaleY;
        invalidate();
    }

    public void setPageHeight(float pageHeight) {
        this.pageHeight = pageHeight;
    }

    public void setDragEnabled(boolean enabled) {
        this.dragEnabled = enabled;
    }

    public void setScaleDetector(ScaleGestureDetector detector) {
        this.scaleDetector = detector;
    }

    public void setOnTextElementSelectedListener(OnTextElementSelectedListener listener) {
        this.listener = listener;
    }

    public boolean isDragEnabled() {
        return dragEnabled;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void setSelectedIndex(int index) {
        this.selectedIndex = index;
        invalidate();
    }

    public void clearSelection() {
        selectedIndex = -1;
        invalidate();
        if (listener != null) listener.onSelectionCleared();
    }

    public void addTextElement(TextElement element) {
        textElements.add(element);
        selectedIndex = textElements.size() - 1;
        invalidate();
        if (listener != null) listener.onTextSelected(element, selectedIndex);
    }

    public void removeElement(int index) {
        if (index >= 0 && index < textElements.size()) {
            TextElement elem = textElements.get(index);
            if (elem.isNew) {
                textElements.remove(index);
            } else {
                elem.isDeleted = true;
            }
            selectedIndex = -1;
            invalidate();
            if (listener != null) listener.onSelectionCleared();
        }
    }

    public void updateElement(int index, TextElement updated) {
        if (index >= 0 && index < textElements.size()) {
            textElements.set(index, updated);
            invalidate();
        }
    }

    /**
     * Convert PDF coordinates (origin at bottom-left) to screen coordinates.
     */
    private RectF pdfToScreen(TextElement elem) {
        float screenX = elem.x * scaleX;
        // PDF Y is from bottom; screen Y is from top
        float screenY = (pageHeight - elem.y) * scaleY;
        float textWidth = estimateTextWidth(elem.text, elem.fontSize) * scaleX;
        float textHeight = elem.fontSize * scaleY;
        return new RectF(screenX, screenY - textHeight, screenX + textWidth, screenY);
    }

    /**
     * Rough estimation of text width in PDF points.
     */
    private float estimateTextWidth(String text, float fontSize) {
        if (text == null || text.isEmpty()) return fontSize * 2;
        // Average character width ~0.5 * fontSize
        return text.length() * fontSize * 0.5f;
    }

    private RectF getMaskRect(float pdfX, float pdfY, String text, float fontSize) {
        float textW = estimateTextWidth(text, fontSize) * 1.1f;
        float textH = fontSize * 1.2f;
        float bottomY = pdfY - (textH * 0.2f);
        float topY = bottomY + textH;
        
        float screenX = pdfX * scaleX;
        float screenTop = (pageHeight - topY) * scaleY;
        float screenBottom = (pageHeight - bottomY) * scaleY;
        float screenWidth = textW * scaleX;
        
        return new RectF(screenX, screenTop, screenX + screenWidth, screenBottom);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (int i = 0; i < textElements.size(); i++) {
            TextElement elem = textElements.get(i);
            
            if (elem.isDeleted) {
                if (!elem.isNew) {
                    canvas.drawRect(getMaskRect(elem.origX, elem.origY, elem.origText, elem.origFontSize), maskPaint);
                }
                continue;
            }
            
            if (!elem.isNew && elem.isModified) {
                canvas.drawRect(getMaskRect(elem.origX, elem.origY, elem.origText, elem.origFontSize), maskPaint);
            }
            
            RectF rect = pdfToScreen(elem);
            elem.screenRect.set(rect);

            if (elem.isNew || elem.isModified) {
                textPaint.setColor(elem.color);
                textPaint.setTextSize(elem.fontSize * scaleY);
                textPaint.setFakeBoldText(elem.fontStyle != null && elem.fontStyle.contains("bold"));
                if (elem.fontStyle != null && elem.fontStyle.contains("italic")) {
                    textPaint.setTextSkewX(-0.25f);
                } else {
                    textPaint.setTextSkewX(0f);
                }
                canvas.drawText(elem.text, rect.left, rect.bottom, textPaint);
            }

            if (i == selectedIndex) {
                // Draw selection highlight
                canvas.drawRoundRect(rect, 4, 4, selectionPaint);
                canvas.drawRoundRect(rect, 4, 4, hoverPaint);

                // Draw corner handles
                float hs = 8f;
                canvas.drawCircle(rect.left, rect.top, hs, handlePaint);
                canvas.drawCircle(rect.right, rect.top, hs, handlePaint);
                canvas.drawCircle(rect.left, rect.bottom, hs, handlePaint);
                canvas.drawCircle(rect.right, rect.bottom, hs, handlePaint);
            } else {
                // Draw subtle bounds for all text elements
                canvas.drawRoundRect(rect, 4, 4, textBoundsPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (scaleDetector != null) {
            scaleDetector.onTouchEvent(event);
            if (scaleDetector.isInProgress()) {
                // If zooming, ignore dragging/selecting
                return true;
            }
        }

        float touchX = event.getX();
        float touchY = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // Check if touching a text element
                int hitIndex = hitTest(touchX, touchY);

                if (dragEnabled && selectedIndex >= 0 && hitIndex == selectedIndex) {
                    // Start dragging
                    isDragging = true;
                    dragStartX = touchX;
                    dragStartY = touchY;
                    TextElement elem = textElements.get(selectedIndex);
                    elemStartX = elem.x;
                    elemStartY = elem.y;
                    return true;
                }

                if (hitIndex >= 0) {
                    selectedIndex = hitIndex;
                    invalidate();
                    if (listener != null) {
                        listener.onTextSelected(textElements.get(hitIndex), hitIndex);
                    }
                    return true;
                } else {
                    if (selectedIndex >= 0) {
                        clearSelection();
                    }
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDragging && selectedIndex >= 0) {
                    float dx = (touchX - dragStartX) / scaleX;
                    float dy = (touchY - dragStartY) / scaleY;
                    TextElement elem = textElements.get(selectedIndex);
                    elem.x = elemStartX + dx;
                    elem.y = elemStartY - dy; // invert Y for PDF coords
                    elem.isModified = true;
                    invalidate();
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDragging) {
                    isDragging = false;
                    return true;
                }
                break;
        }

        return super.onTouchEvent(event);
    }

    private int hitTest(float touchX, float touchY) {
        // Check in reverse order so topmost elements are selected first
        for (int i = textElements.size() - 1; i >= 0; i--) {
            TextElement elem = textElements.get(i);
            if (elem.isDeleted) continue;
            
            RectF rect = elem.screenRect;
            // Add some padding for easier touch
            RectF padded = new RectF(rect.left - 10, rect.top - 10,
                    rect.right + 10, rect.bottom + 10);
            if (padded.contains(touchX, touchY)) {
                return i;
            }
        }
        return -1;
    }
}
