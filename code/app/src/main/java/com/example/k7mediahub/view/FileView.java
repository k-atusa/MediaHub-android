package com.example.k7mediahub.view;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.k7mediahub.IO1;
import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;
import com.example.k7mediahub.app.SvcMH;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// File list activity
public class FileView extends AppCompatActivity {
    private RecyclerView rv;
    private TextView tTitle, tStat;
    private FileAdp adp;
    private String fld;
    private final List<String> items = new ArrayList<>();
    private final Set<Integer> sel = new HashSet<>();
    private int pg = 0;
    private ActivityResultLauncher<Intent> lch;

    // Standard lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_file);

        fld = getIntent().getStringExtra("folder");
        if (fld == null) fld = "";

        rv = findViewById(R.id.rvFiles);
        tTitle = findViewById(R.id.txtFolderName);
        tStat = findViewById(R.id.txtStatus);
        Button bAdd = findViewById(R.id.btnAddFiles);
        Button bDn = findViewById(R.id.btnDownload);
        Button bPre = findViewById(R.id.btnPrev);
        Button bNxt = findViewById(R.id.btnNext);

        tTitle.setText(fld);
        adp = new FileAdp();
        rv.setLayoutManager(new GridLayoutManager(this, 2));
        rv.setAdapter(adp);

        bPre.setOnClickListener(v -> { if (pg > 0) { pg--; update(); } });
        bNxt.setOnClickListener(v -> { if (pg < (items.size() - 1) / 30) { pg++; update(); } });

        // File selection result handler
        lch = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), res -> {
            if (res.getResultCode() == RESULT_OK && res.getData() != null) {
                List<IO1.VFile> fs = IO1.HandleSelectedFile(res.getData());
                if (!fs.isEmpty()) {
                    ArrayList<String> us = new ArrayList<>();
                    for (IO1.VFile f : fs) us.add(f.GetUri().toString());

                    // Set initial status
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

        // Listen for bus
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            Bundle d = (Bundle) ev.data;
            switch (ev.action) {
                case "FILES_LOADED":
                    if (!d.getString("folder", "").equals(fld)) break;
                    items.clear();
                    ArrayList<String> ns = d.getStringArrayList("names");
                    if (ns != null) items.addAll(ns);
                    sel.clear();
                    pg = 0;
                    update();
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

        // Sync percent
        SVCC1.getChan().IntSlots[0].observe(this, p -> {
            String s = SVCC1.getChan().StringSlots[1].getValue();
            if (s != null && !s.isEmpty()) {
                tStat.setText(s + " (" + p + "%)");
            }
        });
        
        // Sync status text
        SVCC1.getChan().StringSlots[1].observe(this, s -> {
            Integer p = SVCC1.getChan().IntSlots[0].getValue();
            if (s != null && !s.isEmpty()) {
                tStat.setText(s + " (" + (p != null ? p : 0) + "%)");
            }
        });

        bAdd.setOnClickListener(v -> IO1.SelectFile(lch, true));
        bDn.setOnClickListener(v -> {
            if (sel.isEmpty()) return;
            ArrayList<String> s = new ArrayList<>();
            for (int p : sel) s.add(items.get(p));
            
            // Set initial status
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

    // Refresh data
    private void refresh() {
        Bundle b = new Bundle();
        b.putString("folder", fld);
        SVCC1.getChan().SendToSvc("GET_FILES", b);
    }

    // Update UI
    private void update() {
        adp.notifyDataSetChanged();
        tStat.setText(items.size() + " Files");
        View pnl = findViewById(R.id.layoutPagination);
        pnl.setVisibility(items.size() > 30 ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.txtPageInfo)).setText((pg + 1) + " / " + ((items.size() + 29) / 30));
    }

    // File list adapter
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
                    int p = pg * 30 + getAdapterPosition();
                    Intent it = new Intent(FileView.this, MediaView.class);
                    it.putExtra("folder", fld);
                    it.putExtra("file", items.get(p));
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
            String n = items.get(gp);
            h.t.setText(n);
            h.c.setOnCheckedChangeListener(null);
            h.c.setChecked(sel.contains(gp));
            h.c.setOnCheckedChangeListener((v, chk) -> { if (chk) sel.add(gp); else sel.remove(gp); });
            byte[] th = SvcMH.thumbCache.get(fld + "/" + n);
            if (th != null) h.i.setImageBitmap(BitmapFactory.decodeByteArray(th, 0, th.length));
            else h.i.setImageBitmap(null);
        }
        @Override
        public int getItemCount() { return Math.min(30, items.size() - pg * 30); }
    }
}
