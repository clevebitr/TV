package com.fongmi.android.tv.sync;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.R;

/**
 * Overlay that explains why the room is paused while somebody is buffering.
 * <p>
 * It is added to the video container of the player screen so it is hidden together with the video
 * and never covers the controls: it only tells the user that the playback is waiting for a slow member.
 */
public final class SyncPlayOverlay implements SyncPlayManager.Listener {

    private final SyncPlayManager manager = SyncPlayManager.get();
    private final View root;
    private final TextView text;

    private SyncPlayOverlay(@NonNull ViewGroup container) {
        this.root = LayoutInflater.from(container.getContext()).inflate(R.layout.view_sync_buffering, container, false);
        this.text = root.findViewById(R.id.syncBufferingText);
        // Above the player surface, below the widgets and the player controls.
        container.addView(root, Math.min(1, container.getChildCount()));
    }

    public static SyncPlayOverlay attach(@NonNull ViewGroup container) {
        SyncPlayOverlay overlay = new SyncPlayOverlay(container);
        overlay.manager.addListener(overlay);
        overlay.render();
        return overlay;
    }

    public void detach() {
        manager.removeListener(this);
        if (root.getParent() instanceof ViewGroup parent) parent.removeView(root);
    }

    @Override
    public void onBufferingChanged() {
        render();
    }

    public void render() {
        String message = manager.getBufferingMessage();
        boolean show = !message.isEmpty();
        if (show && !message.contentEquals(text.getText())) text.setText(message);
        if (root.getVisibility() != (show ? View.VISIBLE : View.GONE)) root.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
