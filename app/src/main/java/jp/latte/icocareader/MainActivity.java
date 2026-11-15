package jp.latte.icocareader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcF;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {

    private static final String PREFS = "icoca_reader";
    private static final String KEY_READINGS = "readings_json";
    private static final String KEY_DRIVE_URI = "drive_uri";
    private static final String KEY_DARK = "dark_mode";
    private static final int REQ_CREATE_DOC = 1001;
    private static final int SERVICE_BALANCE = 0x008B;
    private static final int SERVICE_HISTORY = 0x090F;

    private NfcAdapter nfcAdapter;
    private SharedPreferences prefs;

    private LinearLayout root;
    private TextView statusText;
    private TextView balanceText;
    private TextView lastReadText;
    private TextView driveStatusText;
    private LinearLayout historyRows;
    private Button cardTab;
    private Button readingsTab;
    private Button nfcSettings;
    private List<String> lastCardHistory = new ArrayList<>();
    private boolean showReadings;
    private boolean scannedThisSession;

    private boolean dark = false;
    private int colorBg;
    private int colorText;
    private int colorAccent;
    private int colorSurface;
    private int colorTint;
    private int colorSub;

    private String lastIdmHex = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        dark = prefs.getBoolean(KEY_DARK, false);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        buildUi();
        refreshAll();
    }

    private void applyColors() {
        colorBg = dark ? 0xFF101C23 : 0xFFF1F6F8;
        colorText = dark ? 0xFFE6F0F4 : 0xFF102C3A;
        colorAccent = dark ? 0xFF9DDDE8 : 0xFF075D70;
        colorSub = dark ? 0xFFBACBD3 : 0xFF49636F;
        colorSurface = dark ? 0xFF1B2B34 : Color.WHITE;
        colorTint = dark ? 0xFF25434D : 0xFFDCEEF2;

        getWindow().setStatusBarColor(colorBg);
        getWindow().setNavigationBarColor(colorBg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private TextView makeText(String text, float sizeSp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private Button makeButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? colorSurface : colorAccent);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22075D70),
                rounded(selected ? colorAccent : colorTint, 12), null));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private LinearLayout surface(int color, int padding) {
        LinearLayout layout = column();
        layout.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        layout.setBackground(rounded(color, 20));
        return layout;
    }

    private void buildUi() {
        applyColors();
        root = column();
        root.setBackgroundColor(colorBg);
        root.setPadding(dp(16), dp(12), dp(16), dp(20));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_transit);
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = makeText("ICOCAリーダー", 21, true, colorText);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(10);
        titleParams.rightMargin = dp(10);
        title.setAccessibilityHeading(true);
        header.addView(title, titleParams);
        Button themeButton = makeButton(dark ? "ライト" : "ダーク");
        themeButton.setContentDescription(dark ? "ライトモードに切り替え" : "ダークモードに切り替え");
        themeButton.setOnClickListener(view -> {
            dark = !dark;
            prefs.edit().putBoolean(KEY_DARK, dark).apply();
            buildUi();
            refreshAll();
        });
        header.addView(themeButton);
        add(root, header, 0);

        LinearLayout balance = surface(0xFF10465B, 20);
        add(balance, makeText("最後に読み取った残高", 14, true, 0xFFBFE9F2), 0);
        balanceText = makeText("未読み取り", 42, true, Color.WHITE);
        add(balance, balanceText, 2);
        lastReadText = makeText("", 13, false, 0xFFD1E9F0);
        add(balance, lastReadText, 2);
        add(root, balance, 14);

        LinearLayout scan = surface(colorTint, 14);
        statusText = makeText("", 15, true, colorAccent);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(scan, statusText, 0);
        nfcSettings = makeButton("NFC設定を開く");
        styleButton(nfcSettings, true);
        nfcSettings.setOnClickListener(view -> openNfcSettings());
        add(scan, nfcSettings, 8);
        add(root, scan, 10);

        LinearLayout history = surface(colorSurface, 14);
        LinearLayout tabs = new LinearLayout(this);
        cardTab = makeButton("カード利用");
        readingsTab = makeButton("読み取り記録");
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        tabs.addView(cardTab, tabParams);
        LinearLayout.LayoutParams rightTab = new LinearLayout.LayoutParams(tabParams);
        rightTab.leftMargin = dp(6);
        tabs.addView(readingsTab, rightTab);
        cardTab.setContentDescription("カード利用履歴");
        readingsTab.setContentDescription("読み取り記録");
        cardTab.setOnClickListener(view -> {
            showReadings = false;
            renderHistory();
        });
        readingsTab.setOnClickListener(view -> {
            showReadings = true;
            renderHistory();
        });
        add(history, tabs, 0);
        historyRows = column();
        add(history, historyRows, 8);
        add(root, history, 14);

        LinearLayout storage = surface(colorSurface, 14);
        TextView storageTitle = makeText("記録の保存・共有", 17, true, colorText);
        storageTitle.setAccessibilityHeading(true);
        add(storage, storageTitle, 0);
        driveStatusText = makeText("", 13, false, colorSub);
        add(storage, driveStatusText, 4);
        Button driveButton = makeButton("Driveの保存先を選ぶ");
        driveButton.setOnClickListener(view -> startCreateDocument());
        add(storage, driveButton, 10);
        Button shareButton = makeButton("記録をJSONで共有");
        shareButton.setOnClickListener(view -> shareJsonFile());
        add(storage, shareButton, 6);
        add(root, storage, 14);

        TextView version = makeText("バージョン " + BuildConfig.VERSION_NAME, 13, false, colorSub);
        version.setGravity(Gravity.CENTER);
        add(root, version, 16);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void openNfcSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_NFC_SETTINGS));
        } catch (android.content.ActivityNotFoundException unavailable) {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        }
    }

    private void refreshNfcStatus() {
        boolean disabled = nfcAdapter != null && !nfcAdapter.isEnabled();
        nfcSettings.setVisibility(disabled ? View.VISIBLE : View.GONE);
        if (nfcAdapter == null) {
            statusText.setText("この端末はNFCに対応していません");
        } else if (disabled) {
            statusText.setText("読み取りにはNFCをオンにしてください");
        } else {
            statusText.setText("カードをスマホの背面にかざす");
        }
    }

    private void refreshAll() {
        refreshNfcStatus();
        JSONObject latest = loadReadings().optJSONObject(0);
        balanceText.setText(latest == null ? "未読み取り" : yen(latest.optInt("balance", 0)));
        lastReadText.setVisibility(latest == null ? View.GONE : View.VISIBLE);
        if (latest != null) {
            lastReadText.setText("最終読取  " + displayTime(latest.optString("readAt", "")));
        }
        renderHistory();

        String uri = prefs.getString(KEY_DRIVE_URI, "");
        driveStatusText.setText(uri == null || uri.isEmpty()
                ? "Drive自動保存：未設定"
                : "Drive自動保存：設定済み");
    }

    private void renderHistory() {
        styleButton(cardTab, !showReadings);
        styleButton(readingsTab, showReadings);
        historyRows.removeAllViews();
        if (!showReadings) {
            if (lastCardHistory.isEmpty()) {
                addEmptyHistory(scannedThisSession
                        ? "表示できる利用履歴はありません"
                        : "カードをかざすと直近の利用履歴を表示します");
            } else {
                for (String line : lastCardHistory) {
                    String[] fields = line.split("　", 3);
                    addHistoryRow(fields[1] + "　" + fields[0], fields[2]);
                }
            }
            return;
        }

        JSONArray readings = loadReadings();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject record = readings.optJSONObject(i);
            if (record == null) continue;
            int balance = record.optInt("balance", 0);
            JSONObject previous = readings.optJSONObject(i + 1);
            int difference = previous == null ? 0 : balance - previous.optInt("balance", balance);
            String detail = yen(balance);
            if (difference != 0) {
                detail += "  （前回比 " + (difference > 0 ? "+" : "") + yen(difference) + "）";
            }
            addHistoryRow(displayTime(record.optString("readAt", "")), detail);
        }
        if (historyRows.getChildCount() == 0) addEmptyHistory("まだ読み取り記録がありません");
    }

    private void addEmptyHistory(String message) {
        TextView empty = makeText(message, 14, false, colorSub);
        empty.setPadding(dp(2), dp(8), dp(2), dp(8));
        add(historyRows, empty, 0);
    }

    private void addHistoryRow(String label, String amount) {
        if (historyRows.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(colorTint);
            historyRows.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        }
        LinearLayout row = column();
        row.setPadding(dp(2), dp(8), dp(2), dp(8));
        add(row, makeText(label, 13, false, colorSub), 0);
        add(row, makeText(amount, 16, true, colorText), 1);
        add(historyRows, row, 0);
    }

    private String yen(int amount) {
        return String.format(Locale.JAPAN, "%,d円", amount);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshNfcStatus();
        if (nfcAdapter != null) {
            Bundle opts = new Bundle();
            opts.putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250);
            nfcAdapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, opts);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        NfcF nfcF = NfcF.get(tag);
        if (nfcF == null) {
            runOnUiThread(() -> Toast.makeText(this, "FeliCaカードではありませんでした", Toast.LENGTH_SHORT).show());
            return;
        }
        try {
            nfcF.connect();
            byte[] idm = tag.getId();
            lastIdmHex = toHex(idm);

            byte[] balanceBlock = readBlocks(nfcF, idm, SERVICE_BALANCE, new int[]{0});
            if (balanceBlock == null || balanceBlock.length < 16) {
                throw new Exception("残高ブロックを読めませんでした");
            }
            int balance = (balanceBlock[11] & 0xFF) | ((balanceBlock[12] & 0xFF) << 8);

            List<String> cardHist = new ArrayList<>();
            try {
                byte[][] histBlocks = readBlocksMulti(nfcF, idm, SERVICE_HISTORY,
                        new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
                if (histBlocks != null) {
                    for (byte[] b : histBlocks) {
                        String line = parseHistoryBlock(b);
                        if (line != null) cardHist.add(line);
                    }
                }
            } catch (Exception ignored) {
            }
            nfcF.close();
            runOnUiThread(() -> onReadingSuccess(balance, cardHist));
        } catch (Exception e) {
            try { nfcF.close(); } catch (Exception ignored) {}
            runOnUiThread(() -> Toast.makeText(this,
                    "読み取れませんでした。カードを離さず、もう一度かざしてください", Toast.LENGTH_LONG).show());
        }
    }

    private void onReadingSuccess(int balance, List<String> cardHist) {
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib != null) vib.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE));

        JSONArray readings = loadReadings();
        boolean duplicate = false;
        if (readings.length() > 0) {
            JSONObject latest = readings.optJSONObject(0);
            long prevMs = latest.optLong("readAtMs", 0);
            if (latest.optInt("balance", -1) == balance
                    && System.currentTimeMillis() - prevMs < 60_000) {
                duplicate = true;
            }
        }
        if (!duplicate) {
            JSONObject rec = new JSONObject();
            try {
                rec.put("id", UUID.randomUUID().toString());
                rec.put("readAt", isoNow());
                rec.put("readAtMs", System.currentTimeMillis());
                rec.put("balance", balance);
                rec.put("source", "nfc");
                rec.put("idm", lastIdmHex);
            } catch (Exception ignored) {}
            JSONArray next = new JSONArray();
            next.put(rec);
            for (int i = 0; i < readings.length(); i++) next.put(readings.optJSONObject(i));
            saveReadings(next);
            writeDriveFileIfSet();
        }
        lastCardHistory = cardHist;
        scannedThisSession = true;
        refreshAll();
        Toast.makeText(this, duplicate ? "同じ残高のため記録は追加しませんでした"
                : "記録しました", Toast.LENGTH_SHORT).show();
    }

    private String parseHistoryBlock(byte[] b) {
        if (b == null || b.length < 16) return null;
        boolean allZero = true;
        for (byte v : b) if (v != 0) { allZero = false; break; }
        if (allZero) return null;
        int dateVal = ((b[4] & 0xFF) << 8) | (b[5] & 0xFF);
        int year = 2000 + ((dateVal >> 9) & 0x7F);
        int month = (dateVal >> 5) & 0x0F;
        int day = dateVal & 0x1F;
        int process = b[1] & 0xFF;
        int balanceAfter = (b[10] & 0xFF) | ((b[11] & 0xFF) << 8);
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        return String.format(Locale.JAPAN, "%04d/%02d/%02d　%s　残高 %,d円",
                year, month, day, processLabel(process), balanceAfter);
    }

    private String processLabel(int code) {
        switch (code) {
            case 1: return "運賃支払い";
            case 2: return "チャージ";
            case 3: return "券売機";
            case 5: return "精算";
            case 6: return "窓口";
            case 7: return "新規発行";
            case 8: return "控除";
            case 13: return "バス";
            case 15: return "バス";
            case 16: return "再発行";
            case 17: return "支払い";
            case 19: return "入金";
            case 70: return "物販";
            case 72: return "特典チャージ";
            default: return "利用・種別" + code;
        }
    }

    private byte[] readBlocks(NfcF nfcF, byte[] idm, int serviceCode, int[] blocks) throws Exception {
        byte[][] multi = readBlocksMulti(nfcF, idm, serviceCode, blocks);
        return multi == null || multi.length == 0 ? null : multi[0];
    }

    private byte[][] readBlocksMulti(NfcF nfcF, byte[] idm, int serviceCode, int[] blocks) throws Exception {
        ByteArrayOutputStream cmd = new ByteArrayOutputStream();
        cmd.write(0);
        cmd.write(0x06);
        cmd.write(idm, 0, 8);
        cmd.write(1);
        cmd.write(serviceCode & 0xFF);
        cmd.write((serviceCode >> 8) & 0xFF);
        cmd.write(blocks.length);
        for (int block : blocks) {
            cmd.write(0x80);
            cmd.write(block & 0xFF);
        }
        byte[] raw = cmd.toByteArray();
        raw[0] = (byte) raw.length;
        byte[] resp = nfcF.transceive(raw);
        if (resp == null || resp.length < 13) return null;
        if ((resp[1] & 0xFF) != 0x07) return null;
        if (resp[10] != 0 || resp[11] != 0) return null;
        int numBlocks = resp[12] & 0xFF;
        byte[][] out = new byte[numBlocks][16];
        for (int i = 0; i < numBlocks; i++) {
            int start = 13 + i * 16;
            if (start + 16 > resp.length) return null;
            System.arraycopy(resp, start, out[i], 0, 16);
        }
        return out;
    }

    private JSONArray loadReadings() {
        String s = prefs.getString(KEY_READINGS, "[]");
        try {
            return new JSONArray(s == null ? "[]" : s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveReadings(JSONArray arr) {
        prefs.edit().putString(KEY_READINGS, arr.toString()).apply();
    }

    private String buildJson() {
        try {
            JSONObject rootObj = new JSONObject();
            rootObj.put("app", "icoca-reader");
            rootObj.put("version", 1);
            rootObj.put("exportedAt", isoNow());
            JSONObject card = new JSONObject();
            card.put("type", "ICOCA");
            card.put("idm", lastIdmHex);
            rootObj.put("card", card);
            rootObj.put("readings", loadReadings());
            return rootObj.toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void startCreateDocument() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "icoca_records.json");
        try {
            startActivityForResult(intent, REQ_CREATE_DOC);
        } catch (Exception e) {
            Toast.makeText(this, "保存先を選ぶ画面を開けませんでした", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CREATE_DOC && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
            prefs.edit().putString(KEY_DRIVE_URI, uri.toString()).apply();
            writeDriveFileIfSet();
            refreshAll();
            Toast.makeText(this, "連携ファイルを設定しました", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeDriveFileIfSet() {
        String uriStr = prefs.getString(KEY_DRIVE_URI, "");
        if (uriStr == null || uriStr.isEmpty()) return;
        Uri uri = Uri.parse(uriStr);
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new Exception("no stream");
            out.write(buildJson().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Toast.makeText(this, "連携ファイルへ書き込めませんでした。設定から選び直してください",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareJsonFile() {
        try {
            File dir = new File(getCacheDir(), "exports");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "icoca_records.json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(buildJson().getBytes(StandardCharsets.UTF_8));
            }
            Uri uri = FileProvider.getUriForFile(this,
                    "jp.latte.icocareader.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "記録ファイルの送り先"));
        } catch (Exception e) {
            Toast.makeText(this, "共有ファイルを作れませんでした", Toast.LENGTH_LONG).show();
        }
    }

    private String isoNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.JAPAN);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Tokyo"));
        return fmt.format(new Date());
    }

    private String displayTime(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02X", b & 0xFF));
        return sb.toString();
    }
}
