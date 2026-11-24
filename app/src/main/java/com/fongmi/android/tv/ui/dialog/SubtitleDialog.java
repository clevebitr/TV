package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.SubtitleView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogSubtitleBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

public final class SubtitleDialog extends BaseBottomSheetDialog {

    private static final float DEFAULT_BOTTOM_POSITION = 0.08f;
    private static final float MIN_BOTTOM_POSITION = 0.0f;
    private static final float MAX_BOTTOM_POSITION = 0.5f;
    private static final float POSITION_STEP = 0.005f;
    private static final float MIN_TEXT_SIZE = 0.02f;
    private static final float MAX_TEXT_SIZE = 0.2f;
    private static final float TEXT_SIZE_STEP = 0.002f;

    private DialogSubtitleBinding binding;
    private SubtitleView subtitleView;
    private PlayerManager player;

    public static SubtitleDialog create() {
        return new SubtitleDialog();
    }

    public SubtitleDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public SubtitleDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SubtitleDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean isFull() {
        return Util.isFullscreen(getActivity());
    }

    @Override
    protected boolean transparent() {
        return isFull();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSubtitleBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        int count = binding.getRoot().getChildCount();
        if (isFull()) for (int i = 0; i < count; i++) ((ImageView) binding.getRoot().getChildAt(i)).getDrawable().setTint(MDColor.WHITE);
    }

    @Override
    protected void initEvent() {
        binding.up.setOnClickListener(this::onUp);
        binding.down.setOnClickListener(this::onDown);
        binding.large.setOnClickListener(this::onLarge);
        binding.small.setOnClickListener(this::onSmall);
        binding.reset.setOnClickListener(this::onReset);
    }

    private void onUp(View view) {
        setPosition(getPosition() + POSITION_STEP);
    }

    private void onDown(View view) {
        setPosition(getPosition() - POSITION_STEP);
    }

    private void onLarge(View view) {
        setTextSize(getTextSize() + TEXT_SIZE_STEP);
    }

    private void onSmall(View view) {
        setTextSize(getTextSize() - TEXT_SIZE_STEP);
    }

    private float getPosition() {
        float position = PlayerSetting.getSubtitlePosition();
        return position == 0 ? DEFAULT_BOTTOM_POSITION : position;
    }

    private float getTextSize() {
        float size = PlayerSetting.getSubtitleTextSize();
        return size == 0 ? SubtitleView.DEFAULT_TEXT_SIZE_FRACTION : size;
    }

    private void setPosition(float position) {
        PlayerSetting.putSubtitlePosition(Math.min(Math.max(position, MIN_BOTTOM_POSITION), MAX_BOTTOM_POSITION));
        if (subtitleView != null) subtitleView.setBottomPosition(PlayerSetting.getSubtitlePosition());
        applySubtitleStyle();
    }

    private void setTextSize(float size) {
        PlayerSetting.putSubtitleTextSize(Math.min(Math.max(size, MIN_TEXT_SIZE), MAX_TEXT_SIZE));
        if (subtitleView != null) subtitleView.setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
        applySubtitleStyle();
    }

    private void onReset(View view) {
        PlayerSetting.putSubtitleTextSize(0.0f);
        PlayerSetting.putSubtitlePosition(0.0f);
        if (subtitleView != null) subtitleView.reset();
        applySubtitleStyle();
    }

    private void applySubtitleStyle() {
        if (player != null && !player.isReleased()) player.setSubtitleStyle();
    }

    @Override
    public void onResume() {
        super.onResume();
        getDialog().getWindow().setLayout(ResUtil.dp2px(isFull() ? 232 : 216), -1);
    }
}
