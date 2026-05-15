package com.editing.fluxpdf;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter that renders PDF pages on-demand for continuous vertical scrolling.
 * Uses a single-thread executor so only one page renders at a time,
 * and recycles bitmaps when views are recycled.
 */
public class PdfPagesAdapter extends RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder> {

    private final PDDocument document;
    private final PDFRenderer renderer;
    private final int pageCount;
    private float zoomLevel;
    private final ExecutorService renderExecutor = Executors.newFixedThreadPool(2);

    public PdfPagesAdapter(PDDocument document, PDFRenderer renderer, float zoomLevel) {
        this.document = document;
        this.renderer = renderer;
        this.pageCount = document.getNumberOfPages();
        this.zoomLevel = zoomLevel;
    }

    public void setZoomLevel(float zoom) {
        this.zoomLevel = zoom;
    }

    @NonNull
    @Override
    public PageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pdf_page, parent, false);
        return new PageViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PageViewHolder holder, int position) {
        // Clear previous image and set a placeholder height based on page aspect ratio
        holder.imageView.setImageBitmap(null);
        holder.currentPage = position;

        // Set approximate height so the RecyclerView can lay out correctly
        try {
            PDRectangle box = document.getPage(position).getMediaBox();
            float pageW = box.getWidth();
            float pageH = box.getHeight();
            int screenWidth = holder.itemView.getContext().getResources().getDisplayMetrics().widthPixels;
            float ratio = pageH / pageW;
            holder.imageView.setMinimumHeight((int) (screenWidth * ratio));
        } catch (Exception ignored) {
            holder.imageView.setMinimumHeight(800);
        }

        // Render in background
        final int pageIndex = position;
        renderExecutor.submit(() -> {
            try {
                if (holder.currentPage != pageIndex) return; // view was recycled

                PDRectangle mediaBox = document.getPage(pageIndex).getMediaBox();
                float scale = safeRenderScale(mediaBox.getWidth(), mediaBox.getHeight(), zoomLevel);
                Bitmap bitmap = renderer.renderImage(pageIndex, scale);

                if (holder.currentPage != pageIndex) {
                    bitmap.recycle();
                    return;
                }

                holder.imageView.post(() -> {
                    if (holder.currentPage == pageIndex) {
                        holder.imageView.setImageBitmap(bitmap);
                        holder.renderedBitmap = bitmap;
                    } else {
                        bitmap.recycle();
                    }
                });
            } catch (Exception e) {
                // Silently fail for individual pages
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull PageViewHolder holder) {
        super.onViewRecycled(holder);
        holder.imageView.setImageBitmap(null);
        if (holder.renderedBitmap != null && !holder.renderedBitmap.isRecycled()) {
            holder.renderedBitmap.recycle();
            holder.renderedBitmap = null;
        }
    }

    @Override
    public int getItemCount() {
        return pageCount;
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
    }

    private float safeRenderScale(float pageW, float pageH, float desiredScale) {
        float maxDim = Math.max(pageW, pageH) * desiredScale;
        if (maxDim > 4096f) {
            desiredScale = 4096f / Math.max(pageW, pageH);
        }
        float totalPixels = (pageW * desiredScale) * (pageH * desiredScale);
        if (totalPixels > 16_000_000f) {
            desiredScale = (float) Math.sqrt(16_000_000.0 / (pageW * pageH));
        }
        return Math.max(desiredScale, 0.25f);
    }

    static class PageViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        Bitmap renderedBitmap;
        int currentPage = -1;

        PageViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.ivPageImage);
        }
    }
}
