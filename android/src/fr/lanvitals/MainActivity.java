package fr.lanvitals;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff0a121e;
    private static final int PANEL = 0xff101d2d;
    private static final int BORDER = 0xff213349;
    private static final int TEXT = 0xffedf6ff;
    private static final int MUTED = 0xffa0b3c9;
    private static final int TEAL = 0xff5decc4;
    private static final int BLUE = 0xff77b9ff;
    private static final int PURPLE = 0xffc9a9ff;
    private static final int AMBER = 0xffffca86;
    private static final int HISTORY = 60;

    private Session session;
    private LinearLayout screen;
    private TextView status;
    private Button connectButton;
    private EditText password;
    private AlertDialog dialog;
    private JSONObject latest;
    private final double[][] history = new double[4][HISTORY];
    private int samples;
    private int next;
    private Card cpu, ram, network, disk;
    private TextView host, uptime;
    private boolean dashboard;
    private boolean live;
    private int connectionStatus;
    private int loginMessage;
    private Context localized;
    private Locale locale;
    private Credentials credentials;
    private boolean saved;
    private Page page = Page.DASHBOARD;
    private String selectedDisk;
    private DetailView detail;

    private enum Page { DASHBOARD, NETWORK, STORAGE, DISK, MEMORY }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        credentials = new Credentials(getPreferences(MODE_PRIVATE));
        loadLanguage(getPreferences(MODE_PRIVATE).getString("language",
                Locale.getDefault().getLanguage().equals("en") ? "en" : "fr"));
        String secret = pin() == null ? null : credentials.load();
        saved = secret != null;
        showLogin(R.string.lan_hint);
        if (saved) start(secret);
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (dashboard) showPage();
    }

    @Override public void onBackPressed() {
        if (dashboard && page != Page.DASHBOARD) open(page == Page.DISK ? Page.STORAGE : Page.DASHBOARD);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        Session previous = session;
        session = null;
        if (previous != null) previous.client.close();
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private String pin() {
        return getPreferences(MODE_PRIVATE).getString("tls_pin", null);
    }

    private void loadLanguage(String language) {
        locale = "en".equals(language) ? Locale.UK : Locale.FRANCE;
        Configuration configuration = new Configuration(getResources().getConfiguration());
        configuration.setLocale(locale);
        localized = createConfigurationContext(configuration);
    }

    private String tr(int resource, Object... arguments) {
        return arguments.length == 0 ? localized.getString(resource) : localized.getString(resource, arguments);
    }

    private void chooseLanguage(String language) {
        if (locale.getLanguage().equals(language)) return;
        getPreferences(MODE_PRIVATE).edit().putString("language", language).apply();
        loadLanguage(language);
        if (dashboard) {
            showPage();
        } else {
            String draft = password.getText().toString();
            showLogin(loginMessage);
            password.setText(draft);
        }
    }

    private LinearLayout flags() {
        LinearLayout flags = new LinearLayout(this);
        flag(flags, "fr", "Français", R.drawable.flag_fr);
        flag(flags, "en", "English", R.drawable.flag_en);
        return flags;
    }

    private void flag(LinearLayout row, String language, String name, int image) {
        boolean active = locale.getLanguage().equals(language);
        ImageButton button = new ImageButton(this);
        button.setImageResource(image);
        button.setContentDescription(name);
        button.setSelected(active);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setBackground(shape(PANEL, active ? TEAL : BORDER, 10));
        button.setOnClickListener(view -> chooseLanguage(language));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(50), dp(48));
        params.leftMargin = dp(7);
        row.addView(button, params);
    }

    private void setup(boolean center) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(center);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            }
            return insets;
        });
        LinearLayout outer = vertical();
        if (center) outer.setGravity(Gravity.CENTER);
        int horizontal = dp(wide() ? 32 : 20);
        outer.setPadding(horizontal, dp(center ? 20 : 26), horizontal, dp(24));
        scroll.addView(outer, new ScrollView.LayoutParams(-1, -2));
        screen = vertical();
        outer.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        if (center) {
            LinearLayout content = screen;
            outer.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int width = Math.max(0, Math.min(dp(460), right - left - 2 * horizontal));
                if (content.getLayoutParams().width != width) {
                    content.setLayoutParams(new LinearLayout.LayoutParams(width, -2));
                }
            });
        }
        setContentView(scroll);
        scroll.requestApplyInsets();
    }

    private void showLogin(int message) {
        dashboard = false;
        live = false;
        page = Page.DASHBOARD;
        loginMessage = message;
        latest = null;
        next = samples = 0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setup(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        icon.setBackground(shape(PANEL, BORDER, 19));
        icon.setClipToOutline(true);
        LinearLayout intro = new LinearLayout(this);
        intro.setGravity(Gravity.CENTER_VERTICAL);
        intro.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        intro.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        intro.addView(flags());
        add(screen, intro, 0);
        add(screen, label("LAN Vitals", 36, TEXT, true), 18);
        add(screen, label(tr(R.string.tagline), 16, MUTED, false), 6);
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(18), dp(20), dp(16));
        form.setBackground(shape(PANEL, BORDER, 20));
        add(screen, form, 24);
        add(form, label(tr(R.string.password_label), 14, TEXT, true), 0);
        password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        password.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        password.setTextSize(17);
        password.setTextColor(TEXT);
        password.setHintTextColor(MUTED);
        password.setHint(tr(saved ? R.string.saved_password : R.string.password_hint));
        password.setPadding(dp(14), dp(10), dp(14), dp(10));
        password.setBackground(shape(BACKGROUND, BORDER, 10));
        add(form, password, 9);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            start();
            return true;
        });
        connectButton = button(tr(session == null ? R.string.connect : R.string.cancel), true);
        connectButton.setOnClickListener(view -> {
            if (session == null) start();
            else {
                disconnect();
                loginMessage = R.string.cancelled_search;
                connectButton.setText(tr(R.string.connect));
                status.setText(tr(loginMessage));
            }
        });
        add(form, connectButton, 14);
        status = label(tr(message), 13, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(form, status, 13);
        if (pin() != null) {
            Button forget = button(tr(R.string.forget), false);
            forget.setOnClickListener(view -> {
                disconnect();
                credentials.clear();
                saved = false;
                getPreferences(MODE_PRIVATE).edit().remove("tls_pin").apply();
                showLogin(R.string.forgotten);
            });
            add(screen, forget, 10);
        }
        screen.setFocusableInTouchMode(true);
        screen.requestFocus();
    }

    private void start() {
        if (session != null) return;
        String secret = password.getText().toString();
        if (secret.isEmpty() && saved) secret = credentials.load();
        if (secret == null || secret.isEmpty() || secret.codePointCount(0, secret.length()) > 256) {
            password.setError(tr(R.string.password_length));
            return;
        }
        start(secret);
    }

    private void start(String secret) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(password.getWindowToken(), 0);
        password.getText().clear();
        screen.requestFocus();
        connectButton.setText(tr(R.string.cancel));
        session = new Session(secret);
        new Thread(session.client, "lan-vitals").start();
    }

    private void showDashboard() {
        dashboard = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setup(false);
        if (!wide()) add(screen, toolbar(null), 0);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title = vertical();
        TextView brand = label("LAN VITALS", 12, TEAL, true);
        brand.setLetterSpacing(0.16f);
        add(title, brand, 0);
        host = label(tr(R.string.server), wide() ? 37 : 30, TEXT, true);
        host.setMaxLines(1);
        host.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(title, host, 4);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button exit = button(tr(R.string.disconnect), false);
        exit.setOnClickListener(view -> {
            disconnect();
            showLogin(R.string.disconnected);
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-2, -2);
        exitParams.leftMargin = dp(8);
        top.addView(exit, exitParams);
        if (wide()) top.addView(flags());
        add(screen, top, 0);

        status = label(tr(connectionStatus), 14, live ? TEAL : AMBER, true);
        uptime = label("", 14, MUTED, false);
        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(uptime);
        add(screen, info, 10);

        cpu = new Card(tr(R.string.cpu), TEAL, new Chart(0, -1, TEAL, BLUE, true));
        ram = new Card(tr(R.string.memory), PURPLE, new Chart(1, -1, PURPLE, BLUE, true));
        network = new Card(tr(R.string.network), BLUE, new Chart(2, 3, BLUE, AMBER, false));
        disk = new Card(tr(R.string.storage), AMBER, null);
        openOnClick(ram, Page.MEMORY);
        openOnClick(network, Page.NETWORK);
        openOnClick(disk, Page.STORAGE);
        if (wide()) {
            add(screen, row(cpu, ram), 23);
            add(screen, row(network, disk), 14);
        } else {
            for (Card card : new Card[]{cpu, ram, network, disk}) add(screen, card, 14);
        }
        if (latest != null) render();
    }

    private LinearLayout row(Card left, Card right) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -1, 1);
        first.rightMargin = dp(14);
        row.addView(left, first);
        row.addView(right, new LinearLayout.LayoutParams(0, -1, 1));
        return row;
    }

    private void receive(JSONObject values) {
        latest = values;
        double used = values.optLong("ram_used");
        double total = values.optLong("ram_total");
        history[0][next] = values.optDouble("cpu", 0);
        history[1][next] = percentage(used, total);
        history[2][next] = values.optDouble("rx", 0);
        history[3][next] = values.optDouble("tx", 0);
        samples = Math.min(samples + 1, HISTORY);
        next = (next + 1) % HISTORY;
        live = true;
        connectionStatus = R.string.live;
        if (!dashboard) showDashboard();
        else if (page == Page.DASHBOARD) render();
        else detail.render(values);
        setTextIfChanged(status, tr(connectionStatus));
        status.setTextColor(TEAL);
    }

    private void render() {
        JSONObject data = latest;
        host.setText(data.optString("host", tr(R.string.server)));
        long seconds = data.optLong("uptime");
        long hours = seconds / 3600;
        String elapsed = hours >= 24 ? tr(R.string.days_hours, hours / 24, hours % 24)
                : hours > 0 ? tr(R.string.hours_minutes, hours, seconds / 60 % 60) : tr(R.string.minutes, seconds / 60);
        uptime.setText(tr(R.string.uptime, elapsed));
        cpu.update(percent(data.optDouble("cpu", 0)), tr(R.string.cpu_details,
                data.optInt("cores"), String.format(locale, "%.2f", data.optDouble("load", 0))));
        long ramUsed = data.optLong("ram_used"), ramTotal = data.optLong("ram_total");
        ram.update(percent(percentage(ramUsed, ramTotal)), tr(R.string.ram_details, size(ramUsed), size(ramTotal)));
        network.update("↓ " + speed(data.optLong("rx")), tr(R.string.send, speed(data.optLong("tx"))));
        long diskUsed = data.optLong("disk_used"), diskTotal = data.optLong("disk_total");
        double diskPercent = percentage(diskUsed, diskTotal);
        disk.update(percent(diskPercent), tr(R.string.disk_details, size(data.optLong("disk_free")), data.optString("disk_path")));
        disk.gauge.level = diskPercent;
        disk.gauge.invalidate();
    }

    private String percent(double value) {
        return String.format(locale, "%.0f %%", value);
    }

    private static double percentage(double part, double total) {
        return total > 0 ? Math.max(0, Math.min(100, 100 * part / total)) : 0;
    }

    private String size(long bytes) {
        String[] units = tr(R.string.byte_units).split(",");
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(locale, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private String speed(long bytes) { return size(bytes) + "/s"; }

    private LinearLayout toolbar(Page back) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        if (back != null) {
            Button button = button("‹  " + tr(R.string.back), false);
            button.setOnClickListener(view -> open(back));
            bar.addView(button);
        }
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(flags());
        return bar;
    }

    private void openOnClick(Card card, Page destination) {
        card.makeInteractive(() -> open(destination));
    }

    private void open(Page destination) {
        page = destination;
        showPage();
    }

    private void showPage() {
        if (page == Page.DASHBOARD) {
            showDashboard();
            return;
        }
        setup(false);
        add(screen, toolbar(page == Page.DISK ? Page.STORAGE : Page.DASHBOARD), 0);
        int titleResource;
        switch (page) {
            case NETWORK: titleResource = R.string.network_title; break;
            case STORAGE: titleResource = R.string.storage_title; break;
            case MEMORY: titleResource = R.string.memory_title; break;
            default: titleResource = R.string.storage_title;
        }
        JSONObject disk = page == Page.DISK ? findDisk(latest.optJSONArray("disks")) : null;
        String title = disk == null ? tr(titleResource) : "/dev/" + disk.optString("name");
        add(screen, label(title, wide() ? 34 : 27, TEXT, true), 12);
        status = label(tr(connectionStatus), 14, live ? TEAL : AMBER, true);
        add(screen, status, 8);
        detail = new DetailView();
        add(screen, detail.content, 8);
        detail.render(latest);
    }

    private JSONObject findDisk(JSONArray disks) {
        if (disks == null) return null;
        for (int i = 0; i < disks.length(); i++) {
            JSONObject disk = disks.optJSONObject(i);
            if (disk != null && disk.optString("id").equals(selectedDisk)) return disk;
        }
        return null;
    }

    private String deviceDescription(JSONObject device) {
        String kind = device.optString("kind");
        switch (kind) {
            case "disk": kind = tr(R.string.device_disk); break;
            case "part": kind = tr(R.string.device_partition); break;
            case "loop": kind = tr(R.string.device_loop); break;
            case "rom": kind = tr(R.string.device_optical); break;
        }
        List<String> description = new ArrayList<>();
        description.add(kind);
        description.add(size(device.optLong("size")));
        for (String key : new String[]{"transport", "model"}) {
            String value = device.optString(key);
            if (!value.isEmpty()) description.add(value);
        }
        return android.text.TextUtils.join("  ·  ", description);
    }

    private String grouped(long value) { return String.format(locale, "%,d", value); }
    private String pair(String left, String right) { return "↓ " + left + "   ↑ " + right; }

    private final class DetailView {
        final LinearLayout content = vertical();
        final Map<String, Section> sections = new LinkedHashMap<>();
        private List<String> layout = new ArrayList<>();

        void render(JSONObject data) {
            switch (page) {
                case NETWORK:
                    renderList(data.optJSONArray("interfaces"), false);
                    break;
                case STORAGE:
                    renderList(data.optJSONArray("disks"), true);
                    break;
                case MEMORY:
                    renderMemory(data.optJSONObject("memory"));
                    break;
                case DISK:
                    renderDisk(findDisk(data.optJSONArray("disks")));
                    break;
                default:
                    break;
            }
        }

        private void begin(List<String> keys) {
            if (layout.equals(keys)) return;
            layout = keys;
            content.removeAllViews();
            sections.clear();
        }

        private void message(int resource) {
            begin(java.util.Collections.singletonList("message"));
            section("message", tr(resource), MUTED).title.setText(tr(resource));
        }

        private Section section(String id, String title, int accent) {
            Section section = sections.get(id);
            if (section == null) {
                section = new Section(title, accent);
                sections.put(id, section);
                add(content, section, 12);
            }
            return section;
        }

        private void renderList(JSONArray items, boolean storage) {
            if (items == null) {
                message(R.string.requires_update);
                return;
            }
            if (items.length() == 0) {
                message(R.string.no_devices);
                return;
            }
            String idKey = storage ? "id" : "name";
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) keys.add(item.optString(idKey));
            }
            begin(keys);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString(idKey);
                String name = item.optString("name");
                if (storage) {
                    Section card = section(id, "/dev/" + name + "   ›", AMBER);
                    card.subtitle(deviceDescription(item));
                    if (!card.isClickable()) card.makeInteractive(() -> {
                        selectedDisk = id;
                        open(Page.DISK);
                    });
                } else {
                    renderInterface(section(id, name, BLUE), item);
                }
            }
        }

        private void renderInterface(Section card, JSONObject item) {
            int state = !item.optBoolean("up") ? R.string.down
                    : item.optBoolean("carrier") ? R.string.up : R.string.no_carrier;
            String mac = item.optString("mac");
            card.subtitle(tr(state) + "  ·  MTU " + item.optInt("mtu") + (mac.isEmpty() ? "" : "  ·  " + mac));
            JSONArray addresses = item.optJSONArray("addresses");
            List<String> lines = new ArrayList<>();
            if (addresses != null) {
                for (int i = 0; i < addresses.length(); i++) lines.add(addresses.optString(i));
            }
            card.row(R.string.addresses, lines.isEmpty() ? "—" : android.text.TextUtils.join("\n", lines));
            card.row(R.string.download, speed(item.optLong("rx")));
            card.row(R.string.upload, speed(item.optLong("tx")));
            card.row(R.string.totals, pair(size(item.optLong("rx_total")), size(item.optLong("tx_total"))));
            card.row(R.string.packets, pair(grouped(item.optLong("rx_packets")), grouped(item.optLong("tx_packets"))));
            card.row(R.string.errors, pair(grouped(item.optLong("rx_errors")), grouped(item.optLong("tx_errors"))));
            card.row(R.string.dropped, pair(grouped(item.optLong("rx_dropped")), grouped(item.optLong("tx_dropped"))));
        }

        private void renderMemory(JSONObject memory) {
            if (memory == null) {
                message(R.string.requires_update);
                return;
            }
            begin(java.util.Arrays.asList("ram", "swap"));
            Section ram = section("ram", tr(R.string.ram), PURPLE);
            ram.row(R.string.ram_used, size(latest.optLong("ram_used")));
            ram.row(R.string.ram_total, size(memory.optLong("MemTotal")));
            ram.row(R.string.ram_available, size(memory.optLong("MemAvailable")));
            ram.row(R.string.ram_free, size(memory.optLong("MemFree")));
            ram.row(R.string.ram_cache, size(memory.optLong("Cached")));
            ram.row(R.string.ram_buffers, size(memory.optLong("Buffers")));
            ram.row(R.string.ram_reclaimable, size(memory.optLong("SReclaimable")));
            ram.row(R.string.ram_shared, size(memory.optLong("Shmem")));
            ram.row(R.string.ram_slab, size(memory.optLong("Slab")));
            ram.row(R.string.ram_dirty, size(memory.optLong("Dirty")));
            Section swap = section("swap", tr(R.string.swap), BLUE);
            long total = memory.optLong("SwapTotal"), free = memory.optLong("SwapFree");
            swap.row(R.string.swap_used, size(Math.max(0, total - free)));
            swap.row(R.string.swap_total, size(total));
            swap.row(R.string.swap_free, size(free));
            swap.row(R.string.swap_cached, size(memory.optLong("SwapCached")));
        }

        private void renderDisk(JSONObject disk) {
            if (disk == null) {
                message(R.string.device_unavailable);
                return;
            }
            if (!disk.has("read_bps")) {
                message(R.string.io_unavailable);
                return;
            }
            begin(java.util.Collections.singletonList("io"));
            Section io = section("io", deviceDescription(disk), AMBER);
            io.row(R.string.read, ioRate(disk, "read"));
            io.row(R.string.write, ioRate(disk, "write"));
            io.row(R.string.read_await, latency(disk, "read_ms"));
            io.row(R.string.write_await, latency(disk, "write_ms"));
            io.row(R.string.busy, String.format(locale, "%.1f %%", disk.optDouble("busy")));
            io.row(R.string.queue, String.format(locale, "%.2f", disk.optDouble("queue")));
            io.row(R.string.in_flight, grouped(disk.optLong("in_flight")));
            io.row(R.string.read_total, size(disk.optLong("read_total")));
            io.row(R.string.write_total, size(disk.optLong("write_total")));
        }

        private String ioRate(JSONObject disk, String direction) {
            return speed(disk.optLong(direction + "_bps")) + "  ·  "
                    + String.format(locale, "%.1f IOPS", disk.optDouble(direction + "_iops"));
        }

        private String latency(JSONObject disk, String key) {
            return disk.isNull(key) ? "—" : String.format(locale, "%.2f ms", disk.optDouble(key));
        }
    }

    private final class Section extends LinearLayout {
        final TextView title;
        final TextView subtitle;
        final Map<Integer, TextView> rows = new LinkedHashMap<>();

        Section(String text, int accent) {
            super(MainActivity.this);
            setOrientation(VERTICAL);
            setPadding(dp(18), dp(14), dp(18), dp(14));
            setBackground(shape(PANEL, BORDER, 16));
            title = label(text, 17, accent, true);
            add(this, title, 0);
            subtitle = label("", 14, MUTED, false);
            subtitle.setVisibility(GONE);
            add(this, subtitle, 6);
        }

        void subtitle(String value) {
            setTextIfChanged(subtitle, value);
            subtitle.setVisibility(VISIBLE);
        }

        void row(int name, String value) {
            TextView data = rows.get(name);
            if (data == null) {
                LinearLayout line = new LinearLayout(MainActivity.this);
                TextView heading = label(tr(name), 14, MUTED, false);
                LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
                left.rightMargin = dp(10);
                line.addView(heading, left);
                data = label("", 14, TEXT, true);
                data.setGravity(Gravity.END);
                line.addView(data, new LinearLayout.LayoutParams(0, -2, 1));
                add(this, line, 9);
                rows.put(name, data);
            }
            setTextIfChanged(data, value);
        }

        void makeInteractive(Runnable action) {
            makePanelInteractive(this, action, 16);
        }
    }

    private void setTextIfChanged(TextView view, String value) {
        if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void makePanelInteractive(View view, Runnable action, int radius) {
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x33ffffff), shape(PANEL, BORDER, radius), null));
        view.setOnClickListener(pressed -> action.run());
        view.setFocusable(true);
    }

    private boolean wide() { return getResources().getConfiguration().screenWidthDp >= 700; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        label.setIncludeFontPadding(false);
        return label;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTextColor(primary ? BACKGROUND : TEXT);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(primary ? 0x330a121e : 0x33ffffff),
                shape(primary ? TEAL : PANEL, primary ? TEAL : BORDER, 11), null));
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable shape(int fill, int edge, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), edge);
        return shape;
    }

    private final class Card extends LinearLayout {
        final TextView value, details, indicator;
        final Chart chart;
        final Gauge gauge;

        Card(String title, int accent, Chart chart) {
            super(MainActivity.this);
            this.chart = chart;
            setOrientation(VERTICAL);
            setPadding(dp(19), dp(16), dp(19), dp(15));
            setBackground(shape(PANEL, BORDER, 18));
            LinearLayout heading = new LinearLayout(MainActivity.this);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            heading.addView(label(title, 15, accent, true), new LinearLayout.LayoutParams(0, -2, 1));
            indicator = label("›", 18, MUTED, true);
            indicator.setVisibility(GONE);
            heading.addView(indicator);
            add(this, heading, 0);
            value = label("—", 33, TEXT, true);
            add(this, value, 8);
            details = label("", 13, MUTED, false);
            if (accent == BLUE) details.setTextColor(AMBER);
            add(this, details, 4);
            gauge = chart == null ? new Gauge() : null;
            LinearLayout.LayoutParams plot = new LinearLayout.LayoutParams(-1, dp(66));
            plot.topMargin = dp(15);
            addView(chart != null ? chart : gauge, plot);
        }

        void update(String number, String description) {
            value.setText(number);
            details.setText(description);
            if (chart != null) chart.invalidate();
        }

        void makeInteractive(Runnable action) {
            makePanelInteractive(this, action, 18);
            indicator.setVisibility(VISIBLE);
        }
    }

    private final class Gauge extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        double level;

        Gauge() { super(MainActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f;
            float radius = dp(5);
            paint.setColor(BORDER);
            canvas.drawRoundRect(0, y - radius, getWidth(), y + radius, radius, radius, paint);
            paint.setColor(AMBER);
            canvas.drawRoundRect(0, y - radius, (float) (getWidth() * level / 100), y + radius, radius, radius, paint);
        }
    }

    private final class Chart extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int first, second, color, secondary;
        final boolean fixed;

        Chart(int first, int second, int color, int secondary, boolean fixed) {
            super(MainActivity.this);
            this.first = first;
            this.second = second;
            this.color = color;
            this.secondary = secondary;
            this.fixed = fixed;
        }

        @Override protected void onDraw(Canvas canvas) {
            float height = getHeight() - dp(3);
            float width = getWidth() - dp(2);
            paint.setColor(BORDER);
            paint.setStrokeWidth(dp(1));
            for (int i = 0; i < 3; i++) {
                float y = dp(2) + (height - dp(2)) * i / 2;
                canvas.drawLine(0, y, width, y, paint);
            }
            if (samples == 0) return;
            double max = fixed ? 100 : 1024;
            if (!fixed) {
                for (int i = 0; i < samples; i++) {
                    int index = (next - samples + i + HISTORY) % HISTORY;
                    max = Math.max(max, Math.max(history[first][index], history[second][index]) * 1.12);
                }
            }
            trace(canvas, first, color, width, height, max, true);
            if (second >= 0) trace(canvas, second, secondary, width, height, max, false);
        }

        private void trace(Canvas canvas, int series, int tint, float width, float height, double max, boolean fill) {
            Path line = new Path();
            float start = width * (HISTORY - samples) / (HISTORY - 1);
            float y = height;
            for (int i = 0; i < samples; i++) {
                int index = (next - samples + i + HISTORY) % HISTORY;
                float x = width * (HISTORY - samples + i) / (HISTORY - 1);
                y = (float) (height - (height - dp(2)) * history[series][index] / max);
                if (i == 0) line.moveTo(x, y);
                else line.lineTo(x, y);
            }
            paint.setStyle(Paint.Style.FILL);
            if (fill && samples > 1) {
                Path area = new Path(line);
                area.lineTo(width, height);
                area.lineTo(start, height);
                area.close();
                paint.setShader(new LinearGradient(0, 0, 0, height,
                        (tint & 0xffffff) | 0x55000000, (tint & 0xffffff) | 0x05000000, Shader.TileMode.CLAMP));
                canvas.drawPath(area, paint);
                paint.setShader(null);
            }
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(width, y, dp(2), paint);
        }
    }

    private final class Session implements LanClient.Listener {
        final LanClient client;
        private boolean remembered;

        Session(String secret) {
            client = new LanClient(getSystemService(ConnectivityManager.class), this, secret, pin());
        }

        private void post(Runnable action) {
            runOnUiThread(() -> {
                if (session == this && !isFinishing()) action.run();
            });
        }

        @Override public void status(int message) {
            post(() -> {
                live = false;
                connectionStatus = message;
                if (!dashboard) loginMessage = message;
                status.setText(tr(message));
                status.setTextColor(dashboard ? AMBER : MUTED);
            });
        }

        @Override public LanClient.Endpoint choose(List<LanClient.Endpoint> servers) throws InterruptedException {
            AtomicReference<LanClient.Endpoint> selected = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            post(() -> {
                String[] names = new String[servers.size()];
                for (int i = 0; i < names.length; i++) names[i] = servers.get(i).toString();
                dialog = new AlertDialog.Builder(MainActivity.this).setTitle(tr(R.string.choose_server))
                        .setItems(names, (window, which) -> selected.set(servers.get(which)))
                        .setNegativeButton(tr(R.string.cancel), null).create();
                dialog.setOnDismissListener(window -> done.countDown());
                dialog.show();
            });
            done.await();
            return selected.get();
        }

        @Override public boolean confirm(LanClient.Endpoint server, String fingerprint) throws InterruptedException {
            AtomicReference<Boolean> accepted = new AtomicReference<>(false);
            CountDownLatch done = new CountDownLatch(1);
            post(() -> {
                dialog = new AlertDialog.Builder(MainActivity.this).setTitle(tr(R.string.pair_title, server.host))
                        .setMessage(tr(R.string.pair_message, fingerprint))
                        .setPositiveButton(tr(R.string.confirm), (window, which) -> accepted.set(true))
                        .setNegativeButton(tr(R.string.cancel), null).create();
                dialog.setOnDismissListener(window -> done.countDown());
                dialog.show();
            });
            done.await(90, TimeUnit.SECONDS);
            return accepted.get();
        }

        @Override public void authenticated(String fingerprint, String secret) {
            post(() -> {
                if (remembered) return;
                remembered = true;
                saved = credentials.save(secret, fingerprint);
                if (!saved) android.widget.Toast.makeText(MainActivity.this,
                        tr(R.string.password_store_error), android.widget.Toast.LENGTH_LONG).show();
            });
        }

        @Override public void metrics(JSONObject values) { post(() -> receive(values)); }

        @Override public void stopped(int message) {
            post(() -> {
                disconnect();
                if (message == R.string.wrong_password) {
                    credentials.clear();
                    saved = false;
                }
                showLogin(message);
            });
        }
    }
}
