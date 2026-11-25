package com.fongmi.android.tv.ui.dialog;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterSyncPlayServerBinding;
import com.fongmi.android.tv.databinding.AdapterSyncPlayUserBinding;
import com.fongmi.android.tv.databinding.DialogSyncPlayBinding;
import com.fongmi.android.tv.setting.SyncPlaySetting;
import com.fongmi.android.tv.sync.SyncPlayEndpoint;
import com.fongmi.android.tv.sync.SyncPlayManager;
import com.fongmi.android.tv.sync.SyncPlayUser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SyncPlayDialog extends BaseBottomSheetDialog implements SyncPlayManager.Listener {

    private static final String CUSTOM = "__custom__";
    private static final String ROOM_REGEX = "^[0-9]{6,11}$";

    private enum Page {HOME, CREATE, JOIN, SERVER}

    private final Map<String, Integer> latencies = new LinkedHashMap<>();
    private final Set<String> testing = ConcurrentHashMap.newKeySet();
    private final Random random = new Random();

    private DialogSyncPlayBinding binding;
    private Page page = Page.HOME;
    private Page serverReturn = Page.HOME;
    private String endpoint = "";
    private String customAddress = "";
    private boolean customSelected;
    private boolean customTls = true;
    private int latencyToken;

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
        endpoint = SyncPlaySetting.getEndpoint();
        customTls = SyncPlaySetting.isTls();
        customSelected = !SyncPlayEndpoint.isOfficial(endpoint);
        customAddress = customSelected && SyncPlayEndpoint.parse(endpoint) != null ? endpoint : "";
        binding.nickname.setText(SyncPlaySetting.getNickname());
        binding.createRoom.setText(defaultCreateRoom());
        binding.joinRoom.setText("");
        binding.customServer.setText(customAddress);
        manager.addListener(this);
        render();
        if (manager.isConnected()) manager.requestUsers();
    }

    @Override
    protected void initEvent() {
        binding.back.setOnClickListener(view -> {
            page = page == Page.SERVER ? serverReturn : Page.HOME;
            render();
        });
        binding.create.setOnClickListener(view -> {
            page = Page.CREATE;
            render();
        });
        binding.join.setOnClickListener(view -> {
            page = Page.JOIN;
            binding.joinRoom.setText("");
            render();
        });
        binding.serverRow.setOnClickListener(view -> openServer());
        binding.serverInfoCreate.setOnClickListener(view -> openServer());
        binding.serverInfoJoin.setOnClickListener(view -> openServer());
        binding.copy.setOnClickListener(view -> copyRoom());
        binding.paste.setOnClickListener(view -> pasteRoom());
        binding.regenerate.setOnClickListener(view -> binding.createRoom.setText(randomRoom()));
        binding.submitCreate.setOnClickListener(view -> createRoom());
        binding.submitJoin.setOnClickListener(view -> joinRoom());
        binding.testAll.setOnClickListener(view -> testServers());
        binding.saveServer.setOnClickListener(view -> saveServer());
        binding.leave.setOnClickListener(view -> SyncPlayManager.get().leave());
        binding.refresh.setOnClickListener(view -> SyncPlayManager.get().requestUsers());
        binding.tls.setOnCheckedChangeListener((button, checked) -> {
            if (binding == null || !customSelected) return;
            customTls = checked;
        });
        binding.customServer.setOnFocusChangeListener((view, focused) -> {
            if (!focused && binding != null) updateCustomAddress();
        });
        binding.serverList.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void openServer() {
        if (SyncPlayManager.get().isConnected() || SyncPlayManager.get().isConnecting()) return;
        serverReturn = page == Page.SERVER ? Page.HOME : page;
        page = Page.SERVER;
        render();
        testServers();
    }

    @Override
    public void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void onDestroyView() {
        savePreferences();
        SyncPlayManager.get().removeListener(this);
        super.onDestroyView();
    }

    private String defaultCreateRoom() {
        String saved = SyncPlaySetting.getRoom();
        return isRoom(saved) ? saved : randomRoom();
    }

    private String randomRoom() {
        return String.valueOf(10000000 + random.nextInt(90000000));
    }

    private boolean isRoom(String value) {
        return value != null && value.matches(ROOM_REGEX);
    }

    private String text(android.widget.TextView view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private void createRoom() {
        String nickname = text(binding.nickname);
        if (nickname.isEmpty()) {
            Notify.show(R.string.sync_play_nickname_required);
            return;
        }
        String room = text(binding.createRoom);
        if (!isRoom(room)) {
            Notify.show(R.string.sync_play_room_invalid);
            return;
        }
        SyncPlaySetting.putNickname(nickname);
        SyncPlaySetting.putRoom(room);
        connect(room, nickname);
    }

    private void joinRoom() {
        String nickname = text(binding.nickname);
        if (nickname.isEmpty()) {
            Notify.show(R.string.sync_play_nickname_required);
            return;
        }
        String room = text(binding.joinRoom);
        if (room.isEmpty()) {
            Notify.show(R.string.sync_play_room_empty);
            return;
        }
        if (!isRoom(room)) {
            Notify.show(R.string.sync_play_room_invalid);
            return;
        }
        SyncPlaySetting.putNickname(nickname);
        connect(room, nickname);
    }

    private void connect(String room, String nickname) {
        boolean useTls = SyncPlayEndpoint.isOfficial(endpoint) || tlsValue();
        SyncPlayEndpoint target = SyncPlayEndpoint.parse(endpoint);
        if (target == null) {
            Notify.show(R.string.sync_play_invalid_endpoint);
            page = Page.SERVER;
            render();
            return;
        }
        SyncPlaySetting.putEndpoint(endpoint);
        SyncPlaySetting.putTls(useTls);
        page = Page.HOME;
        SyncPlayManager.get().connect(target, useTls, room, nickname);
        render();
    }

    private boolean tlsValue() {
        if (!customSelected) return true;
        updateCustomAddress();
        return customTls;
    }

    private void updateCustomAddress() {
        if (binding == null || !customSelected) return;
        customAddress = text(binding.customServer);
        endpoint = customAddress;
    }

    private void copyRoom() {
        String room = text(binding.createRoom);
        if (room.isEmpty()) return;
        ClipboardManager manager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("room", room));
        Notify.show(R.string.sync_play_copied);
    }

    private void pasteRoom() {
        ClipboardManager manager = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        CharSequence value = manager == null || manager.getPrimaryClip() == null || manager.getPrimaryClip().getItemCount() == 0 ? "" : manager.getPrimaryClip().getItemAt(0).coerceToText(requireContext());
        String room = value == null ? "" : value.toString().replaceAll("[^0-9]", "");
        if (room.length() > 11) room = room.substring(0, 11);
        if (room.isEmpty()) {
            Notify.show(R.string.sync_play_paste_empty);
            return;
        }
        binding.joinRoom.setText(room);
        binding.joinRoom.setSelection(room.length());
    }

    private void savePreferences() {
        if (binding == null) return;
        String nickname = text(binding.nickname);
        if (!nickname.isEmpty()) SyncPlaySetting.putNickname(nickname);
        if (SyncPlayEndpoint.parse(endpoint) != null) {
            SyncPlaySetting.putEndpoint(endpoint);
            SyncPlaySetting.putTls(SyncPlayEndpoint.isOfficial(endpoint) || customTls);
        }
    }

    @Override
    public void onSessionChanged() {
        if (getView() == null) return;
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

    @Override
    public void onBufferingChanged() {
        if (getView() == null) return;
        render();
    }

    private void render() {
        if (binding == null) return;
        SyncPlayManager manager = SyncPlayManager.get();
        boolean active = manager.isConnected() || manager.isConnecting();
        boolean home = !active && page == Page.HOME;
        boolean create = !active && page == Page.CREATE;
        boolean join = !active && page == Page.JOIN;
        boolean server = !active && page == Page.SERVER;
        boolean room = active;
        binding.back.setVisibility(!active && page != Page.HOME ? View.VISIBLE : View.GONE);
        binding.homeGroup.setVisibility(home ? View.VISIBLE : View.GONE);
        binding.createGroup.setVisibility(create ? View.VISIBLE : View.GONE);
        binding.joinGroup.setVisibility(join ? View.VISIBLE : View.GONE);
        binding.serverGroup.setVisibility(server ? View.VISIBLE : View.GONE);
        binding.nicknameGroup.setVisibility(create || join ? View.VISIBLE : View.GONE);
        binding.roomGroup.setVisibility(room ? View.VISIBLE : View.GONE);
        binding.refresh.setVisibility(manager.isConnected() ? View.VISIBLE : View.GONE);
        binding.latency.setVisibility(room ? View.VISIBLE : View.GONE);
        binding.title.setText(title());
        binding.serverValue.setText(endpoint);
        binding.serverInfoCreate.setText(ResUtil.getString(R.string.sync_play_server_info, endpoint));
        binding.serverInfoJoin.setText(ResUtil.getString(R.string.sync_play_server_info, endpoint));
        renderStatus(manager.getStatus());
        if (room) renderRoom(manager);
        if (server) renderServers();
    }

    private String title() {
        return switch (page) {
            case CREATE -> ResUtil.getString(R.string.sync_play_create_room_title);
            case JOIN -> ResUtil.getString(R.string.sync_play_join_room_title);
            case SERVER -> ResUtil.getString(R.string.sync_play_server_title);
            default -> ResUtil.getString(R.string.sync_play_title);
        };
    }

    private void renderStatus(String status) {
        binding.status.setText(status);
        binding.status.setVisibility(TextUtils.isEmpty(status) ? View.GONE : View.VISIBLE);
    }

    private void renderRoom(SyncPlayManager manager) {
        String room = manager.getRoom();
        String latency = getLatencyText(manager.getRtt());
        String info = ResUtil.getString(R.string.sync_play_connected, room) + " · " + latency;
        if (manager.isLocalBuffering()) info += " · " + ResUtil.getString(R.string.sync_play_buffering_tag);
        binding.info.setText(info);
        binding.info.setVisibility(TextUtils.isEmpty(room) ? View.GONE : View.VISIBLE);
        binding.latency.setText(latency);
        renderUsers(manager.getUsers(), manager.getUsername());
        binding.leave.setEnabled(true);
    }

    private void renderUsers(List<SyncPlayUser> users, String self) {
        SyncPlayManager manager = SyncPlayManager.get();
        binding.users.removeAllViews();
        for (int i = 0; i < users.size(); i++) {
            SyncPlayUser user = users.get(i);
            AdapterSyncPlayUserBinding item = AdapterSyncPlayUserBinding.inflate(getLayoutInflater(), binding.users, false);
            boolean me = self != null && !self.isEmpty() && self.equals(user.getName());
            item.name.setText(user.getName());
            item.tag.setVisibility(me ? View.VISIBLE : View.GONE);
            String info = getInfo(user);
            if (manager.isBuffering(user.getName())) info = info + " · " + ResUtil.getString(R.string.sync_play_buffering_tag);
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

    private void renderServers() {
        if (binding == null) return;
        binding.serverList.removeAllViews();
        for (String value : SyncPlayEndpoint.OFFICIAL) addServerRow(value, false);
        addServerRow(CUSTOM, true);
        binding.customServer.setVisibility(customSelected ? View.VISIBLE : View.GONE);
        binding.tls.setEnabled(customSelected);
        binding.tls.setChecked(customSelected ? customTls : true);
    }

    private void addServerRow(String value, boolean custom) {
        String address = custom ? (customAddress.isEmpty() ? ResUtil.getString(R.string.sync_play_custom_server) : customAddress) : value;
        AdapterSyncPlayServerBinding row = AdapterSyncPlayServerBinding.inflate(getLayoutInflater(), binding.serverList, false);
        boolean selected = custom ? customSelected : !customSelected && value.equals(endpoint);
        row.address.setText(address);
        row.check.setVisibility(selected ? View.VISIBLE : View.GONE);
        String key = custom ? customAddress : value;
        if (custom && customAddress.isEmpty()) row.latency.setVisibility(View.GONE);
        else if (testing.contains(key)) {
            row.latency.setText("…");
            row.latency.setVisibility(View.VISIBLE);
        } else if (latencies.containsKey(key)) {
            Integer rtt = latencies.get(key);
            row.latency.setText(rtt == null ? ResUtil.getString(R.string.sync_play_latency_fail) : ResUtil.getString(R.string.sync_play_latency_value, rtt));
            row.latency.setVisibility(View.VISIBLE);
        } else {
            row.latency.setText("—");
            row.latency.setVisibility(View.VISIBLE);
        }
        row.getRoot().setOnClickListener(view -> selectServer(value, custom));
        LinearLayoutCompat.LayoutParams params = new LinearLayoutCompat.LayoutParams(LinearLayoutCompat.LayoutParams.MATCH_PARENT, LinearLayoutCompat.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = ResUtil.dp2px(8);
        binding.serverList.addView(row.getRoot(), params);
    }

    private void selectServer(String value, boolean custom) {
        if (custom) {
            customSelected = true;
            binding.customServer.setVisibility(View.VISIBLE);
            binding.customServer.setText(customAddress);
            binding.customServer.requestFocus();
            endpoint = customAddress;
            binding.tls.setEnabled(true);
            binding.tls.setChecked(customTls);
        } else {
            customSelected = false;
            binding.customServer.setVisibility(View.GONE);
            endpoint = value;
            binding.tls.setEnabled(false);
            binding.tls.setChecked(true);
        }
        render();
    }

    private void testServers() {
        int token = ++latencyToken;
        latencies.clear();
        testing.clear();
        List<String> targets = new ArrayList<>(SyncPlayEndpoint.OFFICIAL);
        if (SyncPlayEndpoint.parse(customAddress) != null) targets.add(customAddress);
        testing.addAll(targets);
        renderServers();
        for (String value : targets) {
            boolean useTls = SyncPlayEndpoint.isOfficial(value) || customTls;
            SyncPlayManager.get().testLatency(value, useTls, rtt -> {
                if (getView() == null || token != latencyToken || page != Page.SERVER) return;
                testing.remove(value);
                latencies.put(value, rtt);
                renderServers();
            });
        }
    }

    private void saveServer() {
        if (customSelected) {
            updateCustomAddress();
            if (SyncPlayEndpoint.parse(customAddress) == null) {
                Notify.show(R.string.sync_play_invalid_endpoint);
                return;
            }
            endpoint = customAddress;
        } else {
            if (SyncPlayEndpoint.parse(endpoint) == null) {
                Notify.show(R.string.sync_play_invalid_endpoint);
                return;
            }
        }
        boolean useTls = SyncPlayEndpoint.isOfficial(endpoint) || customTls;
        SyncPlaySetting.putEndpoint(endpoint);
        SyncPlaySetting.putTls(useTls);
        page = serverReturn;
        render();
    }
}
