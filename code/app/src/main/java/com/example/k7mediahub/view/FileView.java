package com.example.k7mediahub.view;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.k7mediahub.IO1;
import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.MHcache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// file list view activity
public class FileView extends AppCompatActivity {
    // connection fields
    private RecyclerView rv;
    private TextView tTitle, tStat;
    private FileAdp adp;
    private String fld;
    private final List<String> allItems = new ArrayList<>();
    private final List<String> items = new ArrayList<>();
    private final Set<Integer> sel = new HashSet<>();
    private int pg = 0;
    private ActivityResultLauncher<Intent> lch;

    // keyword filter state
    private boolean keywordsBuilt = false;
    private final List<String> availKeywords = new ArrayList<>();
    private final Set<String> selectKeywords = new HashSet<>();
    private static final Pattern BRACKET_PATTERN = Pattern.compile("[\\[\\(]([^\\]\\)]+)[\\]\\)]");
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[._\\-\\s]+");
    private final Map<String, Set<String>> tokenCache = new HashMap<>();

    // register activity lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_file);

        // get folder name
        fld = getIntent().getStringExtra("folder");
        if (fld == null) fld = "";

        // get UI components
        rv = findViewById(R.id.rvFiles);
        tTitle = findViewById(R.id.txtFolderName);
        tStat = findViewById(R.id.txtStatus);
        Button bAdd = findViewById(R.id.btnAddFiles);
        Button bDn = findViewById(R.id.btnDownload);
        Button bPre = findViewById(R.id.btnPrev);
        Button bNxt = findViewById(R.id.btnNext);
        ImageButton bKwBottom = findViewById(R.id.btnKeywordBottom);

        tTitle.setText(fld);
        adp = new FileAdp();
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setAdapter(adp);

        // pagination move
        bPre.setOnClickListener(v -> { if (pg > 0) { pg--; update(); } });
        bNxt.setOnClickListener(v -> { if (pg < (items.size() - 1) / 30) { pg++; update(); } });

        // show keyword dialog
        bKwBottom.setOnClickListener(v -> showKeyDialog());

        // file selection launcher
        lch = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                List<IO1.VFile> fs = IO1.HandleSelectedFile(res.getData());
                if (!fs.isEmpty()) {
                    ArrayList<String> us = new ArrayList<>();
                    for (IO1.VFile f : fs) us.add(f.GetUri().toString());

                    tStat.setText("Up: 0/" + us.size() + " (0%)");
                    SVCC1.getChan().SetString(1, "Up: 0/" + us.size());
                    SVCC1.getChan().SetInt(0, 0);

                    Bundle b = new Bundle();
                    b.putString("folder", fld);
                    b.putStringArrayList("uris", us);
                    SVCC1.getChan().SendToSvc("UPLOAD_FILES", b);
                }
            }
        });

        // observe main bus
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            Bundle d = (Bundle) ev.data;
            switch (ev.action) {
                case "FILES_LOADED":
                    if (!d.getString("folder", "").equals(fld)) break;
                    allItems.clear();
                    ArrayList<String> ns = d.getStringArrayList("names");
                    if (ns != null) allItems.addAll(ns);
                    sel.clear();
                    pg = 0;
                    keywordsBuilt = false;
                    if (selectKeywords.isEmpty()) {
                        items.clear();
                        items.addAll(allItems);
                        update();
                    } else {
                        applyFilter();
                    }
                    break;
                case "UPLOAD_PROGRESS":
                    SVCC1.getChan().SetString(1, "Up: " + d.getInt("current") + "/" + d.getInt("total"));
                    break;
                case "DOWNLOAD_PROGRESS":
                    SVCC1.getChan().SetString(1, "Dn: " + d.getInt("current") + "/" + d.getInt("total"));
                    break;
                case "UPLOAD_DONE":
                case "DOWNLOAD_DONE":
                    refresh();
                    break;
            }
        });

        // sync status progress
        Runnable syncProgress = () -> {
            String s = SVCC1.getChan().StringSlots[1].getValue();
            Integer p = SVCC1.getChan().IntSlots[0].getValue();
            if (s != null && !s.isEmpty()) {
                tStat.setText(s + " (" + (p != null ? p : 0) + "%)");
            }
        };
        SVCC1.getChan().IntSlots[0].observe(this, p -> syncProgress.run());
        SVCC1.getChan().StringSlots[1].observe(this, s -> syncProgress.run());

        // refresh thumbnails
        SVCC1.getChan().IntSlots[1].observe(this, cnt -> {
            if (adp != null) adp.notifyDataSetChanged();
        });

        // link add/download button
        bAdd.setOnClickListener(v -> IO1.SelectFile(lch, true));
        bDn.setOnClickListener(v -> {
            if (sel.isEmpty()) return;
            ArrayList<String> s = new ArrayList<>();
            for (int p : sel) s.add(items.get(p));
            
            tStat.setText("Dn: 0/" + s.size() + " (0%)");
            SVCC1.getChan().SetString(1, "Dn: 0/" + s.size());
            SVCC1.getChan().SetInt(0, 0);

            Bundle b = new Bundle();
            b.putString("folder", fld);
            b.putStringArrayList("files", s);
            SVCC1.getChan().SendToSvc("DOWNLOAD_FILES", b);
        });

        refresh();
    }

    // extract tokens from filename
    private static List<String> extractTokens(String nameOnly) {
        List<String> tokens = new ArrayList<>();
        String lower = nameOnly.toLowerCase();

        // get pattern match
        Matcher m = BRACKET_PATTERN.matcher(lower);
        StringBuffer remaining = new StringBuffer();
        while (m.find()) {
            String group = m.group(1).trim();
            if (!group.isEmpty()) tokens.add(group);
            m.appendReplacement(remaining, " ");
        }
        m.appendTail(remaining);

        // split remaining name
        String[] parts = SPLIT_PATTERN.split(remaining);
        for (String p : parts) {
            if (!p.isEmpty()) tokens.add(p);
        }
        return tokens;
    }

    // check if token is valid keyword
    private static boolean isValidKeyword(String token) {
        int byteLen = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c <= 0x7F) byteLen += 1;
            else if (c <= 0x7FF) byteLen += 2;
            else byteLen += 3;
        }
        if (byteLen < 4) return false;
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) return true;
        }
        return false;
    }

    // build keywords list
    private void buildKeywords() {
        availKeywords.clear();
        tokenCache.clear();
        Map<String, Integer> wordCount = new HashMap<>();

        // get tokens from every filename
        for (String fileName : allItems) {
            String nameOnly = fileName;
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx > 0) nameOnly = fileName.substring(0, dotIdx);

            List<String> tokens = extractTokens(nameOnly);
            Set<String> unique = new HashSet<>(tokens);
            tokenCache.put(fileName, unique);
            for (String t : unique) {
                if (isValidKeyword(t)) {
                    wordCount.put(t, wordCount.getOrDefault(t, 0) + 1);
                }
            }
        }

        // need at least 4
        for (Map.Entry<String, Integer> e : wordCount.entrySet()) {
            if (e.getValue() >= 4) {
                availKeywords.add(e.getKey());
            }
        }
        Collections.sort(availKeywords);
        selectKeywords.retainAll(new HashSet<>(availKeywords));
        keywordsBuilt = true;
    }

    // apply keyword filter
    private void applyFilter() {
        items.clear();
        if (selectKeywords.isEmpty()) {
            items.addAll(allItems);
        } else {
            if (!keywordsBuilt) buildKeywords();
            for (String fileName : allItems) {
                Set<String> tokenSet = tokenCache.get(fileName);
                if (tokenSet == null) continue;

                boolean match = true;
                for (String kw : selectKeywords) {
                    if (!tokenSet.contains(kw)) {
                        match = false;
                        break;
                    }
                }
                if (match) items.add(fileName);
            }
        }
        sel.clear();
        pg = 0;
        update();
    }

    // show keyword selection dialog
    private void showKeyDialog() {
        if (!keywordsBuilt) buildKeywords();
        if (availKeywords.isEmpty()) {
            tStat.setText("No keywords found");
            return;
        }

        // keyword array
        String[] kwArray = new String[availKeywords.size()];
        boolean[] checked = new boolean[availKeywords.size()];
        for (int i = 0; i < availKeywords.size(); i++) {
            kwArray[i] = availKeywords.get(i);
            checked[i] = selectKeywords.contains(availKeywords.get(i));
        }

        // show dialog
        new AlertDialog.Builder(this)
            .setTitle("Keyword Filter")
            .setMultiChoiceItems(kwArray, checked, (dialog, which, isChecked) -> {
                if (isChecked) {
                    selectKeywords.add(availKeywords.get(which));
                } else {
                    selectKeywords.remove(availKeywords.get(which));
                }
            })
            .setPositiveButton("Apply", (d, w) -> applyFilter())
            .setNeutralButton("Clear All", (d, w) -> {
                selectKeywords.clear();
                applyFilter();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // refresh file list
    private void refresh() {
        Bundle b = new Bundle();
        b.putString("folder", fld);
        SVCC1.getChan().SendToSvc("GET_FILES", b);
    }

    // update file UI
    private void update() {
        adp.notifyDataSetChanged();
        String filterText = selectKeywords.isEmpty() ? "" : " (filtered)";
        tStat.setText(items.size() + " Files" + filterText);
        View pnl = findViewById(R.id.layoutPagination);
        pnl.setVisibility(items.size() > 30 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.txtPageInfo)).setText((pg + 1) + " / " + ((items.size() + 29) / 30));
    }

    // file adapter
    private class FileAdp extends RecyclerView.Adapter<FileAdp.VH> {
        class VH extends RecyclerView.ViewHolder {
            final CheckBox c;
            final ImageView i;
            final TextView t;
            VH(View v) {
                super(v);
                c = v.findViewById(R.id.chkSelect);
                i = v.findViewById(R.id.imgThumb);
                t = v.findViewById(R.id.txtFileName);
                v.setOnClickListener(view -> {
                    int p = pg * 30 + getBindingAdapterPosition();
                    if (p < 0 || p >= items.size()) return;
                    Intent it = new Intent(FileView.this, MediaView.class);
                    it.putExtra("folder", fld);
                    it.putExtra("file", items.get(p));
                    it.putStringArrayListExtra("fileList", new ArrayList<>(items));
                    startActivity(it);
                });
            }
        }
        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_file, p, false));
        }
        @Override
        public void onBindViewHolder(VH h, int p) {
            int gp = pg * 30 + p;
            if (gp >= items.size()) return;
            String n = items.get(gp);
            h.t.setText(n);
            h.c.setOnCheckedChangeListener(null);
            h.c.setChecked(sel.contains(gp));
            h.c.setOnCheckedChangeListener((v, chk) -> { if (chk) sel.add(gp); else sel.remove(gp); });
            byte[] th = MHcache.GetMemThumb(fld + "/" + n);
            if (th != null) h.i.setImageBitmap(BitmapFactory.decodeByteArray(th, 0, th.length));
            else h.i.setImageBitmap(null);
        }
        @Override
        public int getItemCount() { return Math.min(30, items.size() - pg * 30); }
    }
}
