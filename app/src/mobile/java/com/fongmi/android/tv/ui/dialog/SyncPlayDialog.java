package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSyncPlayBinding;
import com.fongmi.android.tv.setting.SyncPlaySetting;
import com.fongmi.android.tv.sync.SyncPlayEndpoint;
import com.fongmi.android.tv.sync.SyncPlayManager;
import com.fongmi.android.tv.ui.adapter.SyncPlayUserAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Random;

public class SyncPlayDialog extends BaseBottomSheetDialog implements SyncPlayManager.Listener {

    private DialogSyncPlayBinding binding;
    private SyncPlayUserAdapter adapter;
    private int testToken;

    public static SyncPlayDialog create() {
        return new SyncPlayDialog();
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SyncPlayDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogSyncPlayBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        SyncPlayManager manager = SyncPlayManager.get();
        adapter = new SyncPlayUserAdapter();
        binding.recycler.setAdapter(adapter);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.server.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, SyncPlayEndpoint.OFFICIAL));
        binding.server.setText(SyncPlaySetting.getEndpoint(), false);
        binding.tls.setChecked(SyncPlaySetting.isTls());
        binding.nickname.setText(SyncPlaySetting.getNickname());
        binding.room.setText(manager.getRoom());
        manager.addListener(this);
        render();
        test();
    }

    @Override
    protected void initEvent() {
        binding.test.setOnClickListener(view -> test());
        binding.refresh.setOnClickListener(view -> SyncPlayManager.get().requestUsers());
        binding.join.setOnClickListener(view -> connect(false));
        binding.create.setOnClickListener(view -> connect(true));
        binding.leave.setOnClickListener(view -> SyncPlayManager.get().leave());
        binding.server.setOnItemClickListener((parent, view, position, id) -> {
            String endpoint = SyncPlayEndpoint.OFFICIAL.get(position);
            binding.server.setText(endpoint, false);
            binding.tls.setChecked(SyncPlayEndpoint.isOfficial(endpoint));
            test();
        });
    }

    @Override
    public void onDestroyView() {
        SyncPlayManager.get().removeListener(this);
        super.onDestroyView();
    }

    @Override
    public void onSessionChanged() {
        if (getView() == null) return;
        if (SyncPlayManager.get().isConnected()) testToken++;
        render();
    }

    @Override
    public void onUsersChanged() {
        if (getView() == null) return;
        render();
    }

    @Override
    public void onRttChanged() {
        if (getView() == null) return;
        render();
    }

    @Override
    public void onStatusChanged(String status) {
        if (getView() == null) return;
        render();
    }

    private void render() {
        SyncPlayManager manager = SyncPlayManager.get();
        boolean active = manager.isConnected() || manager.isConnecting();
        binding.config.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.roomGroup.setVisibility(active ? View.VISIBLE : View.GONE);
        binding.test.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.latency.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.refresh.setVisibility(manager.isConnected() ? View.VISIBLE : View.GONE);
        String status = manager.getStatus();
        binding.status.setText(status);
        binding.status.setVisibility(TextUtils.isEmpty(status) ? View.GONE : View.VISIBLE);
        if (!active) return;
        String room = manager.getRoom();
        String latency = getLatencyText(manager.getRtt());
        binding.info.setText(ResUtil.getString(R.string.sync_play_connected, room) + " · " + latency);
        binding.info.setVisibility(TextUtils.isEmpty(room) ? View.GONE : View.VISIBLE);
        adapter.setItems(manager.getUsers(), manager.getUsername());
        binding.recycler.setVisibility(adapter.size() > 0 ? View.VISIBLE : View.GONE);
        binding.empty.setVisibility(adapter.size() > 0 ? View.GONE : View.VISIBLE);
        binding.leave.setEnabled(true);
    }

    private String getLatencyText(int rtt) {
        return rtt < 0 ? "—" : ResUtil.getString(R.string.sync_play_latency_value, rtt);
    }

    private void test() {
        String value = binding.server.getText().toString().trim();
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse(value);
        if (endpoint == null) {
            binding.latency.setText(R.string.sync_play_latency_fail);
            return;
        }
        boolean tls = SyncPlayEndpoint.isOfficial(value) || binding.tls.isChecked();
        int token = ++testToken;
        binding.latency.setText("…");
        SyncPlayManager.get().testLatency(value, tls, rtt -> {
            if (token != testToken || getView() == null) return;
            binding.latency.setText(rtt < 0 ? ResUtil.getString(R.string.sync_play_latency_fail) : ResUtil.getString(R.string.sync_play_latency_value, rtt));
        });
    }

    private void connect(boolean create) {
        String value = binding.server.getText().toString().trim();
        SyncPlayEndpoint endpoint = SyncPlayEndpoint.parse(value);
        if (endpoint == null) {
            Notify.show(R.string.sync_play_invalid_endpoint);
            return;
        }
        String nickname = binding.nickname.getText().toString().trim();
        if (nickname.isEmpty()) {
            Notify.show(R.string.sync_play_nickname_required);
            return;
        }
        String room = binding.room.getText().toString().trim();
        if (room.isEmpty()) {
            if (!create) {
                Notify.show(R.string.sync_play_room_required);
                return;
            }
            room = randomRoom();
        }
        boolean tls = SyncPlayEndpoint.isOfficial(value) || binding.tls.isChecked();
        SyncPlaySetting.putEndpoint(value);
        SyncPlaySetting.putNickname(nickname);
        SyncPlaySetting.putTls(tls);
        binding.room.setText(room);
        SyncPlayManager.get().connect(endpoint, tls, room, nickname);
    }

    private String randomRoom() {
        return "TV-" + (10000 + new Random().nextInt(90000));
    }
}
