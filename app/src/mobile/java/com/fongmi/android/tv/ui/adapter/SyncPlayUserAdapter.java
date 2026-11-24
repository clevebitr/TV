package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterSyncPlayUserBinding;
import com.fongmi.android.tv.sync.SyncPlayUser;

import java.util.ArrayList;
import java.util.List;

public class SyncPlayUserAdapter extends RecyclerView.Adapter<SyncPlayUserAdapter.ViewHolder> {

    private final List<SyncPlayUser> items = new ArrayList<>();
    private String self = "";

    public void setItems(List<SyncPlayUser> users, String self) {
        items.clear();
        if (users != null) items.addAll(users);
        this.self = self == null ? "" : self;
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSyncPlayUserBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SyncPlayUser item = items.get(position);
        boolean me = !self.isEmpty() && self.equals(item.getName());
        holder.binding.name.setText(item.getName());
        holder.binding.tag.setVisibility(me ? View.VISIBLE : View.GONE);
        holder.binding.info.setText(getInfo(item));
        holder.binding.info.setVisibility(TextUtils.isEmpty(holder.binding.info.getText()) ? View.GONE : View.VISIBLE);
    }

    private String getInfo(SyncPlayUser item) {
        String room = item.getRoom();
        String file = item.getFile();
        if (TextUtils.isEmpty(file)) return room;
        return TextUtils.isEmpty(room) ? file : room + " · " + file;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSyncPlayUserBinding binding;

        ViewHolder(@NonNull AdapterSyncPlayUserBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
