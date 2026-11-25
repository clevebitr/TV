package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSyncPlayUserBinding;
import com.fongmi.android.tv.databinding.DialogSyncPlayBinding;
import com.fongmi.android.tv.setting.SyncPlaySetting;
import com.fongmi.android.tv.sync.SyncPlayEndpoint;
import com.fongmi.android.tv.sync.SyncPlayManager;
import com.fongmi.android.tv.sync.SyncPlayUser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SyncPlayDialog extends BaseBottomSheetDialog implements SyncPlayManager.Listener {

    private DialogSyncPlayBinding binding;
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
        binding.server.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_1, getServers()));
        binding.server.setText(SyncPlaySetting.getEndpoint(), false);
        binding.tls.setChecked(SyncPlaySetting.isTls());
        binding.nickname.setText(SyncPlaySetting.getNickname());
        binding.room.setText(manager.isConnected() ? manager.getRoom() : SyncPlaySetting.getRoom());
        manager.addListener(this);
        render();
        if (manager.isConnected()) manager.requestUsers();
        test();
    }

    @Override
    protected void initEvent() {
        binding.test.setOnClickListener(view -> test());
        binding.refresh.setOnClickListener(view -> SyncPlayManager.get().requestUsers());
        binding.join.setOnClickListener(view -> connect(false));
        binding.create.setOnClickListener(view -> connect(true));
        binding.leave.setOnClickListener(view -> SyncPlayManager.get().leave());
        binding.server.setOnClickListener(view -> binding.server.showDropDown());
        binding.server.setOnItemClickListener((parent, view, position, id) -> {
            String endpoint = (String) parent.getItemAtPosition(position);
            binding.server.setText(endpoint, false);
            binding.tls.setChecked(SyncPlayEndpoint.isOfficial(endpoint));
            savePreferences();
            test();
        });
        binding.tls.setOnCheckedChangeListener((button, checked) -> savePreferences());
    }

    @Override
    public void onDestroyView() {
        savePreferences();
        SyncPlayManager.get().removeListener(this);
        super.onDestroyView();
    }

    private List<String> getServers() {
        List<String> servers = new ArrayList<>(SyncPlayEndpoint.OFFICIAL);
        String saved = SyncPlaySetting.getEndpoint();
        if (!servers.contains(saved)) servers.add(saved);
        return servers;
    }

    private void savePreferences() {
        if (binding == null) return;
        String server = binding.server.getText().toString().trim();
        if (SyncPlayEndpoint.parse(server) != null) {
            SyncPlaySetting.putEndpoint(server);
            SyncPlaySetting.putTls(SyncPlayEndpoint.isOfficial(server) || binding.tls.isChecked());
        }
        String nickname = binding.nickname.getText().toString().trim();
        if (!nickname.isEmpty()) SyncPlaySetting.putNickname(nickname);
        String room = binding.room.getText().toString().trim();
        if (!room.isEmpty()) SyncPlaySetting.putRoom(room);
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
        renderUsers(manager.getUsers(), manager.getUsername());
        binding.leave.setEnabled(true);
    }

    private void renderUsers(List<SyncPlayUser> users, String self) {
        binding.users.removeAllViews();
        for (int i = 0; i < users.size(); i++) {
            SyncPlayUser user = users.get(i);
            AdapterSyncPlayUserBinding item = AdapterSyncPlayUserBinding.inflate(getLayoutInflater(), binding.users, false);
            boolean me = self != null && !self.isEmpty() && self.equals(user.getName());
            item.name.setText(user.getName());
            item.tag.setVisibility(me ? View.VISIBLE : View.GONE);
            String info = getInfo(user);
            item.info.setText(info);
            item.info.setVisibility(TextUtils.isEmpty(info) ? View.GONE : View.VISIBLE);
            LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.WRAP_CONTENT);
            if (i > 0) params.topMargin = ResUtil.dp2px(8);
            binding.users.addView(item.getRoot(), params);
        }
        binding.users.setVisibility(users.isEmpty() ? View.GONE : View.VISIBLE);
        binding.empty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private String getInfo(SyncPlayUser user) {
        String room = user.getRoom();
        String file = user.getFile();
        if (TextUtils.isEmpty(file)) return room;
        return TextUtils.isEmpty(room) ? file : room + " · " + file;
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
        SyncPlaySetting.putRoom(room);
        binding.room.setText(room);
        SyncPlayManager.get().connect(endpoint, tls, room, nickname);
    }

    private String randomRoom() {
        return "TV-" + (10000 + new Random().nextInt(90000));
    }
}
