package com.streamvault.plugin.adaptivebridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AdaptiveBridgeConfigActivity extends Activity {
    private static final int REQUEST_OPEN_M3U = 1001;
    private static final int COLOR_CANVAS = 0xFF07111B;
    private static final int COLOR_PANEL = 0xEE101B2B;
    private static final int COLOR_PANEL_SOFT = 0x99162435;
    private static final int COLOR_SURFACE = 0xFF162435;
    private static final int COLOR_SURFACE_FOCUSED = 0xFF1E3550;
    private static final int COLOR_STROKE = 0xFF24364C;
    private static final int COLOR_BRAND = 0xFF69A8FF;
    private static final int COLOR_BRAND_MUTED = 0x332E8BFF;
    private static final int COLOR_SUCCESS = 0xFF57D68D;
    private static final int COLOR_SUCCESS_MUTED = 0x3357D68D;
    private static final int COLOR_ERROR = 0xFFFF6B6B;
    private static final int COLOR_ERROR_MUTED = 0x33FF6B6B;
    private static final int COLOR_TEXT_PRIMARY = 0xFFF5F7FB;
    private static final int COLOR_TEXT_SECONDARY = 0xFFBBC6D8;
    private static final int COLOR_TEXT_TERTIARY = 0xFF7F8DA5;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<View> busyControls = new ArrayList<>();
    private final List<SourceEntry> sourceEntries = new ArrayList<>();

    private TextView statusPill;
    private TextView channelPill;
    private TextView providerUrlView;
    private TextView messageView;
    private LinearLayout sourcesListContainer;
    private EditText sourceNameEdit;
    private EditText sourceUrlEdit;
    private Button sourceSaveButton;
    private Button sourceCancelButton;
    private int editingSourceIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        refresh();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(COLOR_CANVAS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(22), dp(24), dp(28));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeaderPanel(), matchWrap());
        root.addView(buildSourcesPanel(), matchWrapTop(12));

        return scroll;
    }

    private View buildHeaderPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(null, false, true);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        panel.body.addView(row, matchWrap());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(titleRow, matchWrap());

        titleRow.addView(text(getString(R.string.app_name), 24, COLOR_TEXT_PRIMARY, Typeface.BOLD), wrapWrap());
        statusPill = pill(getString(R.string.status_disabled), COLOR_ERROR_MUTED, COLOR_ERROR);
        titleRow.addView(statusPill, wrapWrapLeft(10));
        channelPill = pill("0", COLOR_SURFACE, COLOR_TEXT_SECONDARY);
        titleRow.addView(channelPill, wrapWrapLeft(8));

        TextView subtitle = text(getString(R.string.config_subtitle), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        subtitle.setPadding(0, dp(6), 0, 0);
        left.addView(subtitle, matchWrap());

        left.addView(buildProviderRow(), matchWrapTop(12));

        messageView = text("", 13, COLOR_BRAND, Typeface.NORMAL);
        messageView.setPadding(0, dp(10), 0, 0);
        messageView.setVisibility(View.GONE);
        left.addView(messageView, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        actions.setPadding(compact ? 0 : dp(16), compact ? dp(14) : 0, 0, 0);
        row.addView(actions, compact ? matchWrap() : wrapWrap());

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        scroll.addView(buttons, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        actions.addView(scroll, compact ? matchWrap() : wrapWrap());

        Button save = button(getString(R.string.button_save));
        save.setOnClickListener(v -> save());
        buttons.addView(save, wrapWrap());

        Button refresh = button(getString(R.string.button_refresh));
        refresh.setOnClickListener(v -> refresh());
        buttons.addView(refresh, wrapWrapLeft(8));

        return panel.container;
    }

    private View buildProviderRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(8), 0, 0));

        row.addView(pill(getString(R.string.label_provider), COLOR_SURFACE, COLOR_TEXT_SECONDARY), wrapWrap());

        providerUrlView = text("", 13, COLOR_TEXT_SECONDARY, Typeface.NORMAL);
        providerUrlView.setSingleLine(true);
        providerUrlView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        providerUrlView.setPadding(dp(10), 0, dp(10), 0);
        row.addView(providerUrlView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button copy = smallButton(getString(R.string.button_copy));
        copy.setOnClickListener(v -> copyToClipboard(getString(R.string.label_provider), providerUrlView.getText().toString()));
        row.addView(copy, wrapWrap());
        return row;
    }

    private View buildSourcesPanel() {
        boolean compact = isCompactWidth();
        PanelViews panel = panel(getString(R.string.section_sources), false, true);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(12), dp(12), dp(12), dp(12));
        form.setBackground(roundRect(COLOR_PANEL_SOFT, dp(8), 0, 0));
        panel.body.addView(form, matchWrap());

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        editRow.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(editRow, matchWrap());

        sourceNameEdit = editText(getString(R.string.label_source_name), false, 1, 1);
        editRow.addView(sourceNameEdit, compact
                ? matchWrap()
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.32f));

        sourceUrlEdit = editText(getString(R.string.label_source_url), false, 1, 1);
        LinearLayout.LayoutParams urlParams = compact
                ? matchWrapTop(8)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.68f);
        if (!compact) urlParams.leftMargin = dp(10);
        editRow.addView(sourceUrlEdit, urlParams);

        HorizontalScrollView actionsScroll = new HorizontalScrollView(this);
        actionsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sourceActions = new LinearLayout(this);
        sourceActions.setOrientation(LinearLayout.HORIZONTAL);
        sourceActions.setGravity(Gravity.RIGHT);
        actionsScroll.addView(sourceActions, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams actionsParams = matchWrapTop(10);
        form.addView(actionsScroll, actionsParams);

        sourceSaveButton = button(getString(R.string.button_add_source));
        sourceSaveButton.setOnClickListener(v -> saveSourceDraft());
        sourceActions.addView(sourceSaveButton, wrapWrap());

        Button addFile = button(getString(R.string.button_pick_file));
        addFile.setOnClickListener(v -> pickM3uFile());
        sourceActions.addView(addFile, wrapWrapLeft(8));

        sourceCancelButton = button(getString(R.string.button_cancel));
        sourceCancelButton.setVisibility(View.GONE);
        sourceCancelButton.setOnClickListener(v -> clearSourceDraft());
        sourceActions.addView(sourceCancelButton, wrapWrapLeft(8));

        sourcesListContainer = new LinearLayout(this);
        sourcesListContainer.setOrientation(LinearLayout.VERTICAL);
        sourcesListContainer.setPadding(0, dp(12), 0, 0);
        panel.body.addView(sourcesListContainer, matchWrap());

        return panel.container;
    }

    private void pickM3uFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/x-mpegurl",
                "application/vnd.apple.mpegurl",
                "audio/x-mpegurl",
                "text/plain",
                "application/octet-stream"
        });
        try {
            startActivityForResult(intent, REQUEST_OPEN_M3U);
        } catch (Exception error) {
            showMessage(error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_M3U || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getData();
        if (uri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // Some providers grant transient read access only.
        }
        appendSource(uri.toString());
    }

    private void appendSource(String source) {
        if (source == null || source.trim().isEmpty()) return;
        String normalized = source.trim();
        for (SourceEntry entry : sourceEntries) {
            if (entry.url.equalsIgnoreCase(normalized)) {
                showMessage(getString(R.string.message_source_duplicate));
                return;
            }
        }
        sourceEntries.add(new SourceEntry("", normalized));
        renderSources();
        save();
    }

    private void saveSourceDraft() {
        String name = sourceNameEdit.getText().toString().trim();
        String url = sourceUrlEdit.getText().toString().trim();
        if (url.isEmpty()) {
            showMessage(getString(R.string.message_source_url_required));
            return;
        }
        for (int i = 0; i < sourceEntries.size(); i++) {
            if (i != editingSourceIndex && sourceEntries.get(i).url.equalsIgnoreCase(url)) {
                showMessage(getString(R.string.message_source_duplicate));
                return;
            }
        }
        if (editingSourceIndex >= 0 && editingSourceIndex < sourceEntries.size()) {
            sourceEntries.set(editingSourceIndex, new SourceEntry(name, url));
            showMessage(getString(R.string.message_source_updated));
        } else {
            sourceEntries.add(new SourceEntry(name, url));
            showMessage(getString(R.string.message_source_added));
        }
        clearSourceDraft();
        renderSources();
        save();
    }

    private void editSource(int index) {
        if (index < 0 || index >= sourceEntries.size()) return;
        SourceEntry entry = sourceEntries.get(index);
        editingSourceIndex = index;
        sourceNameEdit.setText(entry.name);
        sourceUrlEdit.setText(entry.url);
        sourceSaveButton.setText(getString(R.string.button_update_source));
        sourceCancelButton.setVisibility(View.VISIBLE);
    }

    private void removeSource(int index) {
        if (index < 0 || index >= sourceEntries.size()) return;
        sourceEntries.remove(index);
        clearSourceDraft();
        renderSources();
        save();
        showMessage(getString(R.string.message_source_removed));
    }

    private void clearSourceDraft() {
        editingSourceIndex = -1;
        sourceNameEdit.setText("");
        sourceUrlEdit.setText("");
        sourceSaveButton.setText(getString(R.string.button_add_source));
        sourceCancelButton.setVisibility(View.GONE);
    }

    private void renderSources() {
        if (sourcesListContainer == null) return;
        sourcesListContainer.removeAllViews();
        if (sourceEntries.isEmpty()) {
            sourcesListContainer.addView(text(getString(R.string.message_no_sources), 13, COLOR_TEXT_TERTIARY, Typeface.NORMAL), matchWrap());
            return;
        }
        for (int i = 0; i < sourceEntries.size(); i++) {
            sourcesListContainer.addView(sourceRow(i, sourceEntries.get(i)), matchWrapTop(i == 0 ? 0 : 8));
        }
    }

    private View sourceRow(int index, SourceEntry entry) {
        boolean compact = isCompactWidth();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(compact ? Gravity.START : Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(roundRect(COLOR_PANEL_SOFT, dp(8), 0, 0));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, compact ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = text(sourceDisplayName(index, entry), 14, COLOR_TEXT_PRIMARY, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        left.addView(name, matchWrap());

        TextView url = text(entry.url, 12, COLOR_TEXT_TERTIARY, Typeface.NORMAL);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        left.addView(url, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(compact ? Gravity.START : Gravity.RIGHT);
        actions.setPadding(compact ? 0 : dp(10), compact ? dp(8) : 0, 0, 0);
        row.addView(actions, compact ? matchWrap() : wrapWrap());

        Button edit = smallButton(getString(R.string.button_edit));
        edit.setOnClickListener(v -> editSource(index));
        actions.addView(edit, wrapWrap());

        Button remove = smallButton(getString(R.string.button_remove));
        remove.setOnClickListener(v -> removeSource(index));
        actions.addView(remove, wrapWrapLeft(8));

        return row;
    }

    private String sourceDisplayName(int index, SourceEntry entry) {
        if (entry.name != null && !entry.name.trim().isEmpty()) return entry.name.trim();
        return getString(R.string.label_source_fallback, index + 1);
    }

    private void refresh() {
        runBusy(() -> {
            AdaptiveBridge bridge = AdaptiveBridge.get(this);
            JSONObject values = bridge.configurationValues();
            if (bridge.isEnabled()) {
                startKeepAliveService();
            }
            return values.toString();
        }, raw -> {
            try {
                JSONObject values = new JSONObject(raw);
                updateHeader(values.optString("status"), values.optInt("channelCount"));
                providerUrlView.setText(values.optString("providerUrl"));
                showMessage(values.optString("lastMessage"));
                parseSources(values.optString("source_urls", ""));
                renderSources();
            } catch (Exception error) {
                showMessage(error.toString());
            }
        });
    }

    private void save() {
        runBusy(() -> {
            JSONObject values = new JSONObject()
                    .put("source_urls", serializeSources());
            AdaptiveBridge bridge = AdaptiveBridge.get(this);
            bridge.saveConfiguration(values.toString());
            if (bridge.isEnabled()) {
                startKeepAliveService();
            }
            return getString(R.string.message_settings_saved);
        }, message -> {
            showMessage(message);
            refresh();
        });
    }

    private void runBusy(Worker worker, Result result) {
        showMessage(getString(R.string.message_working));
        setBusy(true);
        executor.execute(() -> {
            String message;
            try {
                message = worker.run();
            } catch (Throwable error) {
                message = error.getMessage() == null ? error.toString() : error.getMessage();
            }
            String finalMessage = message;
            mainHandler.post(() -> {
                setBusy(false);
                result.apply(finalMessage);
            });
        });
    }

    private void startKeepAliveService() {
        startService(new Intent(this, StreamVaultAdaptiveBridgePluginService.class)
                .setAction(StreamVaultAdaptiveBridgePluginService.ACTION_KEEP_ALIVE));
    }

    private void updateHeader(String status, int channels) {
        String normalized = status == null ? "" : status.trim();
        boolean enabled = "ACTIVE".equalsIgnoreCase(normalized) ||
                "ENABLED".equalsIgnoreCase(normalized) ||
                "READY".equalsIgnoreCase(normalized) ||
                normalized.equalsIgnoreCase(getString(R.string.status_ready));
        boolean error = "ERROR".equalsIgnoreCase(normalized);
        int statusBg = error ? COLOR_ERROR_MUTED : enabled ? COLOR_SUCCESS_MUTED : COLOR_ERROR_MUTED;
        int statusColor = error ? COLOR_ERROR : enabled ? COLOR_SUCCESS : COLOR_ERROR;
        String label = error ? getString(R.string.status_error) : enabled ? getString(R.string.status_ready) : getString(R.string.status_disabled);
        statusPill.setText(label);
        statusPill.setTextColor(statusColor);
        statusPill.setBackground(roundRect(statusBg, dp(999), 0, 0));
        channelPill.setText(getString(R.string.label_channel_count, channels));
    }

    private void parseSources(String raw) {
        sourceEntries.clear();
        if (raw == null || raw.trim().isEmpty()) return;
        String[] lines = raw.split("\\r?\\n");
        for (String line : lines) {
            String normalized = line.trim();
            if (normalized.isEmpty()) continue;
            int pipe = normalized.indexOf('|');
            if (pipe > 0 && pipe + 1 < normalized.length()) {
                sourceEntries.add(new SourceEntry(normalized.substring(0, pipe).trim(), normalized.substring(pipe + 1).trim()));
            } else {
                sourceEntries.add(new SourceEntry("", normalized));
            }
        }
    }

    private String serializeSources() {
        StringBuilder out = new StringBuilder();
        for (SourceEntry entry : sourceEntries) {
            if (entry.url == null || entry.url.trim().isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            if (entry.name != null && !entry.name.trim().isEmpty()) {
                out.append(entry.name.trim()).append('|');
            }
            out.append(entry.url.trim());
        }
        return out.toString();
    }

    private void showMessage(String message) {
        if (messageView == null) return;
        String value = message == null ? "" : message.trim();
        messageView.setText(value);
        messageView.setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void copyToClipboard(String label, String value) {
        if (value == null || value.trim().isEmpty()) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            showMessage(getString(R.string.message_copied));
        }
    }

    private void setBusy(boolean busy) {
        for (View control : busyControls) {
            control.setEnabled(!busy);
        }
    }

    private TextView text(String value, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        view.setIncludeFontPadding(true);
        return view;
    }

    private TextView pill(String label, int backgroundColor, int textColor) {
        TextView pill = text(label, 12, textColor, Typeface.BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dp(10), dp(5), dp(10), dp(5));
        pill.setBackground(roundRect(backgroundColor, dp(999), 0, 0));
        return pill;
    }

    private EditText editText(String hint, boolean multiline, int minLines, int maxLines) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(13);
        edit.setTextColor(COLOR_TEXT_PRIMARY);
        edit.setHintTextColor(COLOR_TEXT_TERTIARY);
        edit.setSingleLine(!multiline);
        edit.setMinLines(minLines);
        edit.setMaxLines(maxLines);
        edit.setGravity(multiline ? Gravity.TOP | Gravity.START : Gravity.CENTER_VERTICAL);
        edit.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        edit.setBackground(focusBackground(0x33162435, COLOR_SURFACE_FOCUSED, dp(8), dp(1), COLOR_STROKE));
        edit.setPadding(dp(10), dp(8), dp(10), dp(8));
        return edit;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(COLOR_TEXT_PRIMARY);
        button.setAllCaps(false);
        button.setMinHeight(dp(38));
        button.setMinimumHeight(dp(38));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(focusBackground(COLOR_SURFACE, COLOR_SURFACE_FOCUSED, dp(8), dp(1), COLOR_STROKE));
        busyControls.add(button);
        return button;
    }

    private Button smallButton(String text) {
        Button button = button(text);
        button.setTextSize(12);
        button.setMinHeight(dp(32));
        button.setMinimumHeight(dp(32));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private PanelViews panel(String title, boolean collapsible, boolean expandedByDefault) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(16), dp(16), dp(16), dp(16));
        container.setBackground(roundRect(COLOR_PANEL, dp(8), dp(1), COLOR_STROKE));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        if (title != null && !title.trim().isEmpty()) {
            TextView header = text(title, 17, COLOR_TEXT_PRIMARY, Typeface.BOLD);
            header.setSingleLine(true);
            header.setEllipsize(TextUtils.TruncateAt.END);
            if (collapsible) {
                header.setFocusable(true);
                header.setBackground(focusBackground(0x00000000, COLOR_SURFACE_FOCUSED, dp(8), 0, 0));
                header.setPadding(dp(8), dp(8), dp(8), dp(8));
                header.setText((expandedByDefault ? "- " : "+ ") + title);
                body.setVisibility(expandedByDefault ? View.VISIBLE : View.GONE);
                header.setOnClickListener(v -> {
                    boolean expanded = body.getVisibility() == View.VISIBLE;
                    body.setVisibility(expanded ? View.GONE : View.VISIBLE);
                    header.setText((expanded ? "+ " : "- ") + title);
                });
            }
            container.addView(header, matchWrap());
            LinearLayout.LayoutParams bodyParams = matchWrapTop(10);
            container.addView(body, bodyParams);
        } else {
            container.addView(body, matchWrap());
        }

        return new PanelViews(container, body);
    }

    private GradientDrawable roundRect(int color, int radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    private StateListDrawable focusBackground(int normalColor, int focusedColor, int radius, int strokeWidth, int strokeColor) {
        StateListDrawable drawable = new StateListDrawable();
        drawable.addState(new int[]{android.R.attr.state_focused}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{android.R.attr.state_pressed}, roundRect(focusedColor, radius, strokeWidth, COLOR_BRAND));
        drawable.addState(new int[]{}, roundRect(normalColor, radius, strokeWidth, strokeColor));
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrapWrapLeft(int leftDp) {
        LinearLayout.LayoutParams params = wrapWrap();
        params.leftMargin = dp(leftDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isCompactWidth() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return widthDp > 0 && widthDp < 600;
    }

    private interface Worker {
        String run() throws Exception;
    }

    private interface Result {
        void apply(String value);
    }

    private static final class SourceEntry {
        final String name;
        final String url;

        SourceEntry(String name, String url) {
            this.name = name == null ? "" : name.trim();
            this.url = url == null ? "" : url.trim();
        }
    }

    private static final class PanelViews {
        final LinearLayout container;
        final LinearLayout body;

        PanelViews(LinearLayout container, LinearLayout body) {
            this.container = container;
            this.body = body;
        }
    }
}
