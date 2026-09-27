package com.example.k7mediahub.view;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.k7mediahub.R;
import com.example.k7mediahub.SVCC1;

import java.util.ArrayList;
import java.util.List;

// folder list view activity
public class FolderView extends AppCompatActivity {
    // connection fields
    private RecyclerView rv;
    private TextView tStat;
    private FldAdp adp;
    private final List<String> items = new ArrayList<>();

    // register activity lifecycle
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.view_folder);

        // get UI components
        rv = findViewById(R.id.rvFolders);
        tStat = findViewById(R.id.txtStatus);
        Button bAdd = findViewById(R.id.btnAddFolder);
        adp = new FldAdp();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adp);

        // observe main bus
        SVCC1.getChan().ToMainBus.observe(this, ev -> {
            if (ev == null) return;
            if ("FOLDERS_LOADED".equals(ev.action)) {
                Bundle d = (Bundle) ev.data;
                ArrayList<String> ns = d.getStringArrayList("names");
                items.clear();
                if (ns != null) items.addAll(ns);
                adp.notifyDataSetChanged();
                tStat.setText(items.size() + " Folders");
            }
        });

        // show add folder dialog
        bAdd.setOnClickListener(v -> {
            EditText et = new EditText(this);
            new AlertDialog.Builder(this)
                .setTitle("New Folder")
                .setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) {
                        Bundle b = new Bundle();
                        b.putString("name", n);
                        SVCC1.getChan().SendToSvc("MK_FOLDER", b);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        // fetch folder list
        SVCC1.getChan().SendToSvc("GET_FOLDERS", null);
    }

    // folder adapter
    private class FldAdp extends RecyclerView.Adapter<FldAdp.VH> {
        class VH extends RecyclerView.ViewHolder {
            final TextView t;
            VH(View v) {
                super(v);
                t = v.findViewById(R.id.txtFolderName);
                v.setOnClickListener(view -> {
                    int p = getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) {
                        Intent it = new Intent(FolderView.this, FileView.class);
                        it.putExtra("folder", items.get(p));
                        startActivity(it);
                    }
                });
            }
        }
        @Override
        public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext()).inflate(R.layout.item_folder, p, false);
            return new VH(v);
        }
        @Override
        public void onBindViewHolder(VH h, int p) { h.t.setText(items.get(p)); }
        @Override
        public int getItemCount() { return items.size(); }
    }
}
