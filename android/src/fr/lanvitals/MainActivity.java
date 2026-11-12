package fr.lanvitals;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
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
    private String connectionStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        showLogin("Sur le même réseau Wi-Fi ou Ethernet que le PC.");
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (dashboard) showDashboard();
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

    private void showLogin(String message) {
        dashboard = false;
        live = false;
        latest = null;
        next = samples = 0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setup(true);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.logo);
        icon.setBackground(shape(PANEL, BORDER, 19));
        icon.setClipToOutline(true);
        screen.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
        add(screen, label("LAN Vitals", 36, TEXT, true), 18);
        add(screen, label("Les métriques de votre PC Linux en direct.", 16, MUTED, false), 6);
        LinearLayout form = vertical();
        form.setPadding(dp(20), dp(18), dp(20), dp(16));
        form.setBackground(shape(PANEL, BORDER, 20));
        add(screen, form, 24);
        add(form, label("Mot de passe du serveur", 14, TEXT, true), 0);
        password = new EditText(this);
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setFilters(new InputFilter[]{new InputFilter.LengthFilter(512)});
        password.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        password.setTextSize(17);
        password.setTextColor(TEXT);
        password.setHintTextColor(MUTED);
        password.setHint("Saisir le mot de passe");
        password.setPadding(dp(14), dp(10), dp(14), dp(10));
        password.setBackground(shape(BACKGROUND, BORDER, 10));
        add(form, password, 9);
        password.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            start();
            return true;
        });
        connectButton = button("Trouver le serveur", true);
        connectButton.setOnClickListener(view -> {
            if (session == null) start();
            else {
                disconnect();
                connectButton.setText("Trouver le serveur");
                status.setText("Recherche annulée.");
            }
        });
        add(form, connectButton, 14);
        status = label(message, 13, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(form, status, 13);
        if (pin() != null) {
            Button forget = button("Oublier l’appairage", false);
            forget.setOnClickListener(view -> {
                disconnect();
                getPreferences(MODE_PRIVATE).edit().remove("tls_pin").apply();
                showLogin("Appairage oublié.");
            });
            add(screen, forget, 10);
        }
        screen.setFocusableInTouchMode(true);
        screen.requestFocus();
    }

    private void start() {
        if (session != null) return;
        String secret = password.getText().toString();
        if (secret.isEmpty() || secret.codePointCount(0, secret.length()) > 256) {
            password.setError("Saisissez de 1 à 256 caractères.");
            return;
        }
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(password.getWindowToken(), 0);
        password.getText().clear();
        screen.requestFocus();
        connectButton.setText("Annuler");
        session = new Session(secret);
        new Thread(session.client, "lan-vitals").start();
    }

    private void showDashboard() {
        dashboard = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setup(false);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout title = vertical();
        TextView brand = label("LAN VITALS", 12, TEAL, true);
        brand.setLetterSpacing(0.16f);
        add(title, brand, 0);
        host = label("Serveur Linux", wide() ? 37 : 30, TEXT, true);
        host.setMaxLines(1);
        host.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(title, host, 4);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button exit = button("Déconnecter", false);
        exit.setOnClickListener(view -> {
            disconnect();
            showLogin("Connexion fermée.");
        });
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(-2, -2);
        exitParams.leftMargin = dp(8);
        top.addView(exit, exitParams);
        add(screen, top, 0);

        status = label(connectionStatus, 14, live ? TEAL : AMBER, true);
        uptime = label("", 14, MUTED, false);
        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        info.addView(uptime);
        add(screen, info, 10);

        cpu = new Card("Processeur", TEAL, new Chart(0, -1, TEAL, BLUE, true));
        ram = new Card("Mémoire", PURPLE, new Chart(1, -1, PURPLE, BLUE, true));
        network = new Card("Réseau", BLUE, new Chart(2, 3, BLUE, AMBER, false));
        disk = new Card("Stockage", AMBER, null);
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
        connectionStatus = "●  En direct";
        if (dashboard) render();
        else showDashboard();
        status.setText(connectionStatus);
        status.setTextColor(TEAL);
    }

    private void render() {
        JSONObject data = latest;
        host.setText(data.optString("host", "Serveur Linux"));
        long seconds = data.optLong("uptime");
        long hours = seconds / 3600;
        String elapsed = hours >= 24 ? (hours / 24) + " j " + (hours % 24) + " h"
                : hours > 0 ? hours + " h " + (seconds / 60 % 60) + " min" : (seconds / 60) + " min";
        uptime.setText("Actif depuis " + elapsed);
        cpu.update(percent(data.optDouble("cpu", 0)), data.optInt("cores") + " processeurs logiques  ·  Charge 1 min "
                + String.format(Locale.FRANCE, "%.2f", data.optDouble("load", 0)));
        long ramUsed = data.optLong("ram_used"), ramTotal = data.optLong("ram_total");
        ram.update(percent(percentage(ramUsed, ramTotal)), size(ramUsed) + " utilisés sur " + size(ramTotal));
        network.update("↓ " + size(data.optLong("rx")) + "/s", "↑ " + size(data.optLong("tx")) + "/s  ·  Émission");
        long diskUsed = data.optLong("disk_used"), diskTotal = data.optLong("disk_total");
        double diskPercent = percentage(diskUsed, diskTotal);
        disk.update(percent(diskPercent), size(data.optLong("disk_free")) + " disponibles  ·  " + data.optString("disk_path"));
        disk.gauge.level = diskPercent;
        disk.gauge.invalidate();
    }

    private static String percent(double value) {
        return String.format(Locale.FRANCE, "%.0f %%", value);
    }

    private static double percentage(double part, double total) {
        return total > 0 ? Math.max(0, Math.min(100, 100 * part / total)) : 0;
    }

    private static String size(long bytes) {
        String[] units = {"o", "Kio", "Mio", "Gio", "Tio", "Pio"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.FRANCE, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
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
        final TextView value, details;
        final Chart chart;
        final Gauge gauge;

        Card(String title, int accent, Chart chart) {
            super(MainActivity.this);
            this.chart = chart;
            setOrientation(VERTICAL);
            setPadding(dp(19), dp(16), dp(19), dp(15));
            setBackground(shape(PANEL, BORDER, 18));
            TextView heading = label(title, 15, accent, true);
            add(this, heading, 0);
            value = label("—", 33, TEXT, true);
            add(this, value, 8);
            details = label("", 13, MUTED, false);
            if (title.equals("Réseau")) details.setTextColor(AMBER);
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

        Session(String secret) {
            client = new LanClient(getSystemService(ConnectivityManager.class), this, secret, pin());
        }

        private void post(Runnable action) {
            runOnUiThread(() -> {
                if (session == this && !isFinishing()) action.run();
            });
        }

        @Override public void status(String message) {
            post(() -> {
                live = false;
                connectionStatus = message;
                status.setText(message);
                status.setTextColor(dashboard ? AMBER : MUTED);
            });
        }

        @Override public LanClient.Endpoint choose(List<LanClient.Endpoint> servers) throws InterruptedException {
            AtomicReference<LanClient.Endpoint> selected = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            post(() -> {
                String[] names = new String[servers.size()];
                for (int i = 0; i < names.length; i++) names[i] = servers.get(i).toString();
                dialog = new AlertDialog.Builder(MainActivity.this).setTitle("Choisir le serveur")
                        .setItems(names, (window, which) -> selected.set(servers.get(which)))
                        .setNegativeButton("Annuler", null).create();
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
                dialog = new AlertDialog.Builder(MainActivity.this).setTitle("Appairer " + server.host)
                        .setMessage("Comparez cette empreinte à celle affichée dans le terminal du PC.\n\n" + fingerprint)
                        .setPositiveButton("Elle correspond", (window, which) -> accepted.set(true))
                        .setNegativeButton("Annuler", null).create();
                dialog.setOnDismissListener(window -> done.countDown());
                dialog.show();
            });
            done.await(90, TimeUnit.SECONDS);
            return accepted.get();
        }

        @Override public void paired(String fingerprint) {
            post(() -> getPreferences(MODE_PRIVATE).edit().putString("tls_pin", fingerprint).apply());
        }

        @Override public void metrics(JSONObject values) { post(() -> receive(values)); }

        @Override public void stopped(String message) {
            post(() -> {
                disconnect();
                showLogin(message);
            });
        }
    }
}
