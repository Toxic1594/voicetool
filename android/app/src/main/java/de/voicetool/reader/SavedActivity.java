package de.voicetool.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lists audio files exported by EasyTTS in Music/EasyTTS. Tap to play,
 * long-press to share or delete.
 */
public class SavedActivity extends Activity {

    private static final int REQUEST_READ = 21;

    private static final class Item {
        Uri uri;
        String name;
        long size;
        long date;
        String mime;
    }

    private final List<Item> items = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView empty;

    private MediaPlayer player;
    private Uri playingUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved);

        list = findViewById(R.id.saved_list);
        empty = findViewById(R.id.saved_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                new ArrayList<>());
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        list.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) ->
                playOrStop(items.get(position)));
        list.setOnItemLongClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            showActions(items.get(position));
            return true;
        });

        findViewById(R.id.saved_back).setOnClickListener(v -> finish());

        if (needsReadPermission()) {
            requestPermissions(new String[]{readPermission()}, REQUEST_READ);
        } else {
            reload();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        reload(); // Show what we can access even without the permission.
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        stopPlayer();
        super.onDestroy();
    }

    // -- Data -------------------------------------------------------------
    private boolean needsReadPermission() {
        String permission = readPermission();
        return permission != null
                && checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED;
    }

    private String readPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return "android.permission.READ_MEDIA_AUDIO";
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            return "android.permission.READ_EXTERNAL_STORAGE";
        }
        return null;
    }

    private void reload() {
        items.clear();
        String[] projection;
        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.MIME_TYPE,
            };
            selection = MediaStore.Audio.Media.RELATIVE_PATH + " LIKE ?";
            args = new String[]{"Music/EasyTTS/%"};
        } else {
            projection = new String[]{
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.MIME_TYPE,
            };
            selection = MediaStore.Audio.Media.DATA + " LIKE ?";
            args = new String[]{"%/Music/EasyTTS/%"};
        }

        try (Cursor cursor = getContentResolver().query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                MediaStore.Audio.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                int mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
                while (cursor.moveToNext()) {
                    Item item = new Item();
                    item.uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn));
                    item.name = cursor.getString(nameColumn);
                    item.size = cursor.getLong(sizeColumn);
                    item.date = cursor.getLong(dateColumn) * 1000L;
                    item.mime = cursor.getString(mimeColumn);
                    if (item.mime == null) {
                        item.mime = item.name.endsWith(".wav") ? "audio/wav" : "audio/mp4";
                    }
                    items.add(item);
                }
            }
        } catch (RuntimeException ignored) {
            // Permission missing or provider unavailable: show empty state.
        }

        adapter.clear();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (Item item : items) {
            String line = item.name + "\n"
                    + format.format(new Date(item.date)) + " · "
                    + String.format(Locale.US, "%.1f MB", item.size / 1_000_000.0);
            adapter.add(line);
        }
        adapter.notifyDataSetChanged();
        empty.setText(items.isEmpty() ? getString(R.string.saved_empty) : "");
    }

    // -- Playback ---------------------------------------------------------
    private void playOrStop(Item item) {
        if (playingUri != null && playingUri.equals(item.uri)) {
            stopPlayer();
            return;
        }
        stopPlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(this, item.uri);
            player.setOnCompletionListener(mp -> stopPlayer());
            player.prepare();
            player.start();
            playingUri = item.uri;
            Toast.makeText(this, getString(R.string.saved_playing, item.name),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception exc) {
            stopPlayer();
            Toast.makeText(this, R.string.saved_play_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // Already stopped.
            }
            player.release();
            player = null;
        }
        playingUri = null;
    }

    // -- Actions ----------------------------------------------------------
    private void showActions(Item item) {
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(new CharSequence[]{
                        getString(R.string.saved_share),
                        getString(R.string.saved_delete),
                }, (dialog, which) -> {
                    if (which == 0) {
                        share(item);
                    } else {
                        confirmDelete(item);
                    }
                })
                .show();
    }

    private void share(Item item) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(item.mime)
                .putExtra(Intent.EXTRA_STREAM, item.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, getString(R.string.saved_share)));
    }

    private void confirmDelete(Item item) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.saved_delete_confirm, item.name))
                .setNegativeButton(R.string.saved_delete_no, null)
                .setPositiveButton(R.string.saved_delete_yes, (dialog, which) -> {
                    try {
                        getContentResolver().delete(item.uri, null, null);
                    } catch (RuntimeException ignored) {
                        // Nothing to delete.
                    }
                    reload();
                })
                .show();
    }
}
