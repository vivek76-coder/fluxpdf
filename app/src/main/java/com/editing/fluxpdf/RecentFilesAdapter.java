package com.editing.fluxpdf;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RecentFilesAdapter extends RecyclerView.Adapter<RecentFilesAdapter.ViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(RecentFilesManager.RecentFile file);
    }

    private final List<RecentFilesManager.RecentFile> files;
    private final OnFileClickListener listener;

    public RecentFilesAdapter(List<RecentFilesManager.RecentFile> files, OnFileClickListener listener) {
        this.files = files;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recent_file, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecentFilesManager.RecentFile file = files.get(position);
        holder.name.setText(file.name);
        holder.date.setText(file.getFormattedDate());
        holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name, date;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.fileItemName);
            date = itemView.findViewById(R.id.fileItemDate);
        }
    }
}
