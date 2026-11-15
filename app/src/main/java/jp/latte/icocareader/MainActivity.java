package jp.latte.icocareader;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcF;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
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
    private TextView historyText;
    private TextView cardHistoryText;

    private boolean dark = false;
    private int colorBg;
    private int colorText;
    private int colorAccent;
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
        if (dark) {
            colorBg = Color.rgb(16, 22, 38);
            colorText = Color.rgb(235, 240, 250);
            colorAccent = Color.rgb(120, 160, 255);
            colorSub = Color.rgb(170, 182, 205);
        } else {
            colorBg = Color.WHITE;
            colorText = Color.rgb(11, 32, 71);
            colorAccent = Color.rgb(0, 23, 193);
            colorSub = Color.rgb(90, 104, 128);
        }
    }

    private TextView makeText(String text, float sizeSp, boolean bold, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setLineSpacing(0, 1.4f);
        return tv;
    }

    private Button makeButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(17);
        b.setMinHeight(dp(56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        applyColors();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBg);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));

        TextView title = makeText("ICOCAリーダー", 20, true, colorText);
        root.addView(title);

        statusText = makeText("", 16, false, colorSub);
        root.addView(statusText);

        balanceText = makeText("残高 未読取", 36, true, colorText);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(14), 0, 0);
        balanceText.setLayoutParams(blp);
        root.addView(balanceText);

        lastReadText = makeText("", 15, false, colorSub);
        root.addView(lastReadText);

        Button driveButton = makeButton("Drive連携ファイルを設定");
        driveButton.setOnClickListener(v -> startCreateDocument());
        root.addView(driveButton);

        driveStatusText = makeText("", 14, false, colorSub);
        root.addView(driveStatusText);

        Button shareButton = makeButton("記録ファイルを共有する");
        shareButton.setOnClickListener(v -> shareJsonFile());
        root.addView(shareButton);

        Button themeButton = makeButton(dark ? "ライトモードに切り替え" : "ダークモードに切り替え");
        themeButton.setOnClickListener(v -> {
            dark = !dark;
            prefs.edit().putBoolean(KEY_DARK, dark).apply();
            buildUi();
            refreshAll();
        });
        root.addView(themeButton);

        TextView h1 = makeText("読み取り履歴", 18, true, colorText);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(18), 0, dp(4));
        h1.setLayoutParams(hlp);
        root.addView(h1);
        historyText = makeText("", 15, false, colorText);
        root.addView(historyText);

        TextView h2 = makeText("カードの利用履歴・直近10件", 18, true, colorText);
        h2.setLayoutParams(hlp);
        root.addView(h2);
        cardHistoryText = makeText("まだ読み取っていません", 15, false, colorText);
        root.addView(cardHistoryText);

        TextView version = makeText("バージョン " + BuildConfig.VERSION_NAME, 13, false, colorSub);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(0, dp(24), 0, 0);
        version.setLayoutParams(vlp);
        root.addView(version);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(colorBg);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshAll() {
        if (nfcAdapter == null) {
            statusText.setText("この端末はNFCに対応していません");
        } else if (!nfcAdapter.isEnabled()) {
            statusText.setText("NFCがオフです。端末設定でNFCをオンにしてください");
        } else {
            statusText.setText("カードをスマホの背面にかざしてください");
        }
        JSONArray readings = loadReadings();
        if (readings.length() > 0) {
            JSONObject latest = readings.optJSONObject(0);
            int bal = latest.optInt("balance", -1);
            balanceText.setText("残高 " + String.format(Locale.JAPAN, "%,d", bal) + "円");
            lastReadText.setText("最終読取 " + displayTime(latest.optString("readAt", "")));
        } else {
            balanceText.setText("残高 未読取");
            lastReadText.setText("");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < readings.length(); i++) {
            JSONObject r = readings.optJSONObject(i);
            int bal = r.optInt("balance", 0);
            String line = displayTime(r.optString("readAt", "")) + "　"
                    + String.format(Locale.JAPAN, "%,d", bal) + "円";
            if (i + 1 < readings.length()) {
                int prev = readings.optJSONObject(i + 1).optInt("balance", bal);
                int diff = bal - prev;
                if (diff != 0) {
                    line += " 前回比 " + (diff > 0 ? "+" : "") + String.format(Locale.JAPAN, "%,d", diff) + "円";
                }
            }
            sb.append(line);
            if (i + 1 < readings.length()) sb.append("\n");
        }
        historyText.setText(readings.length() == 0 ? "まだ記録がありません" : sb.toString());

        String uri = prefs.getString(KEY_DRIVE_URI, "");
        driveStatusText.setText(uri == null || uri.isEmpty()
                ? "Drive連携は未設定です。上のボタンで保存先ファイルを指定してください"
                : "Drive連携を設定済みです。読み取るたびに同じファイルへ自動で書き直します");
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        if (!cardHist.isEmpty()) {
            cardHistoryText.setText(String.join("\n", cardHist));
        }
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
