package de.voicetool.reader;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reader UI. Playback runs in ReaderService so it continues in the
 * background and while the screen is off.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "voicetool";
    private static final int REQUEST_NOTIFICATIONS = 7;
    private static final int REQUEST_WRITE = 11;

    private EditText input;
    private SeekBar rateBar;
    private SeekBar pitchBar;
    private SeekBar volumeBar;
    private Spinner voiceSpinner;
    private TextView status;
    private Button readButton;
    private Button saveButton;
    private String pendingSaveText;

    private SharedPreferences prefs;
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;

    private final List<String> voiceIds = new ArrayList<>();
    private String[] shownIds = new String[0];
    private boolean applyingSuggestion;
    private boolean suggestionDone;
    private String currentState = ReaderService.STATE_IDLE;
    private int currentIndex;
    private int currentTotal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        input = findViewById(R.id.input);
        rateBar = findViewById(R.id.rate);
        pitchBar = findViewById(R.id.pitch);
        volumeBar = findViewById(R.id.volume);
        voiceSpinner = findViewById(R.id.voice);
        status = findViewById(R.id.status);
        readButton = findViewById(R.id.speak);

        // Keep text across rotation, but start empty on a normal launch.
        if (savedInstanceState != null) {
            input.setText(savedInstanceState.getString("draft", ""));
        }
        rateBar.setProgress(prefs.getInt("rate", 100));
        pitchBar.setProgress(prefs.getInt("pitch", 100));
        volumeBar.setProgress(prefs.getInt("volume", 100));
        String savedVoice = prefs.getString("voice", "");

        rateBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("rate", value).apply();
                    sendConfig();
                }
            }
        });
        pitchBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("pitch", value).apply();
                    sendConfig();
                }
            }
        });
        volumeBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt("volume", value).apply();
                    sendConfig();
                }
            }
        });

        voiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (position < 0) {
                    return;
                }
                String voice = "";
                if (position > 0 && position - 1 < voiceIds.size()) {
                    voice = voiceIds.get(position - 1);
                }
                if (applyingSuggestion) {
                    return;
                }
                if (!voice.equals(prefs.getString("voice", ""))) {
                    prefs.edit().putString("voice", voice).putBoolean("voiceChosen", true).apply();
                    sendConfig();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        Button stopButton = findViewById(R.id.stop);
        saveButton = findViewById(R.id.save);
        readButton.setOnClickListener(v -> onReadClicked());
        stopButton.setOnClickListener(v -> {
            sendAction(ReaderService.ACTION_STOP);
            setStatus(getString(R.string.status_stopped));
        });
        saveButton.setOnClickListener(v -> saveAudio());
        findViewById(R.id.saved).setOnClickListener(
                v -> startActivity(new Intent(this, SavedActivity.class)));
        findViewById(R.id.about).setOnClickListener(
                v -> startActivity(new Intent(this, AboutActivity.class)));

        if (savedVoice != null && !savedVoice.isEmpty()) {
            // Selection is applied once the service sends the voice list.
            prefs.edit().putString("voice", savedVoice).apply();
        }
        askNotificationPermission();
        if (savedInstanceState == null) {
            handleIntent(getIntent());
        }
    }

    // -- Control the service ---------------------------------------------
    private void sendAction(String action) {
        Intent intent = new Intent(this, ReaderService.class).setAction(action);
        startService(intent);
    }

    private void sendConfig() {
        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_CONFIG)
                .putExtra(ReaderService.EXTRA_RATE, rateBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_PITCH, pitchBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOLUME, volumeBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOICE, prefs.getString("voice", ""));
        startService(intent);
    }

    private void onReadClicked() {
        String text = input.getText().toString().trim();
        boolean active = ReaderService.STATE_SPEAKING.equals(currentState)
                || ReaderService.STATE_PAUSED.equals(currentState);
        if (text.isEmpty() && !active) {
            Toast.makeText(this, R.string.hint_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!text.isEmpty()) {
            }
        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_READ)
                .putExtra(ReaderService.EXTRA_TEXT, text)
                .putExtra(ReaderService.EXTRA_RATE, rateBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_PITCH, pitchBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOLUME, volumeBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOICE, prefs.getString("voice", ""));
        startService(intent);
    }

    private void speakInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.hint_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        speak(text);
    }

    private void speak(String text) {
        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_SPEAK)
                .putExtra(ReaderService.EXTRA_TEXT, text)
                .putExtra(ReaderService.EXTRA_RATE, rateBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_PITCH, pitchBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOLUME, volumeBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOICE, prefs.getString("voice", ""));
        startService(intent);
    }

    // -- Export -----------------------------------------------------------
    private void saveAudio() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.hint_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingSaveText = text;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE);
            return;
        }
        export(text);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_WRITE) {
            return;
        }
        String text = pendingSaveText;
        pendingSaveText = null;
        if (text != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            export(text);
        } else {
            Toast.makeText(this, R.string.storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    private void export(final String text) {
        sendAction(ReaderService.ACTION_STOP);
        saveButton.setEnabled(false);
        setStatus(getString(R.string.status_saving));

        final float rate = rateBar.getProgress() / 100f;
        final float pitch = pitchBar.getProgress() / 100f;
        final String voice = prefs.getString("voice", "");
        new Thread(() -> {
            try {
                Uri uri = AudioExporter.export(this, text, rate, pitch, voice,
                        (done, total) -> runOnUiThread(() -> setStatus(
                                getString(R.string.status_saving_progress, done, total))));
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    setStatus(getString(R.string.status_saved));
                    Toast.makeText(MainActivity.this, R.string.status_saved,
                            Toast.LENGTH_LONG).show();
                });
            } catch (Exception exc) {
                runOnUiThread(() -> {
                    saveButton.setEnabled(true);
                    setStatus(getString(R.string.status_save_failed));
                    Toast.makeText(MainActivity.this,
                            getString(R.string.status_save_failed) + " " + exc.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "audio-export").start();
    }

    // -- Status from the service -----------------------------------------
    private void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ReaderService.ACTION_STATE.equals(intent.getAction())) {
                    return;
                }
                currentState = intent.getStringExtra(ReaderService.EXTRA_STATE);
                currentIndex = intent.getIntExtra(ReaderService.EXTRA_INDEX, 0);
                currentTotal = intent.getIntExtra(ReaderService.EXTRA_TOTAL, 0);
                String[] labels = intent.getStringArrayExtra(ReaderService.EXTRA_VOICE_LABELS);
                String[] ids = intent.getStringArrayExtra(ReaderService.EXTRA_VOICE_IDS);
                String suggested = intent.getStringExtra(ReaderService.EXTRA_SUGGESTED_VOICE);
                if (labels != null && ids != null) {
                    updateVoices(labels, ids, suggested);
                }
                showState();
            }
        };
        IntentFilter filter = new IntentFilter(ReaderService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void updateVoices(String[] labels, String[] ids, String suggested) {
        if (Arrays.equals(ids, shownIds) && (suggestionDone || suggested == null || suggested.isEmpty())) {
            return;
        }
        shownIds = ids;
        voiceIds.clear();
        voiceIds.addAll(Arrays.asList(ids));

        List<String> entries = new ArrayList<>();
        entries.add(getString(R.string.voice_system));
        for (String label : labels) {
            entries.add(label);
        }
        applyingSuggestion = true;  // building the adapter must not save anything
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);

        String saved = prefs.getString("voice", "");
        int selection = -1;
        boolean useSuggestion = false;
        for (int i = 0; i < voiceIds.size(); i++) {
            if (voiceIds.get(i).equals(saved)) {
                selection = i + 1;
                break;
            }
        }
        // No explicit choice yet: suggest the best voice for the device language.
        if (selection < 0 && !prefs.getBoolean("voiceChosen", false)
                && suggested != null && !suggested.isEmpty()) {
            for (int i = 0; i < voiceIds.size(); i++) {
                if (voiceIds.get(i).equals(suggested)) {
                    selection = i + 1;
                    useSuggestion = true;
                    prefs.edit().putString("voice", suggested).apply();
                    break;
                }
            }
        }
        suggestionDone = true;
        if (selection > 0) {
            voiceSpinner.setSelection(selection, false);
        }
        if (useSuggestion) {
            sendConfig();
        }
        // Allow selection again only after the posted adapter callbacks.
        voiceSpinner.post(() -> applyingSuggestion = false);
    }

    private void showState() {
        if (ReaderService.STATE_ERROR.equals(currentState)) {
            readButton.setText(R.string.speak);
            setStatus(getString(R.string.status_error));
            return;
        }
        if (ReaderService.STATE_SPEAKING.equals(currentState)) {
            readButton.setText(R.string.pause);
            if (currentTotal > 0) {
                setStatus(getString(R.string.status_progress_speaking,
                        Math.min(currentIndex + 1, currentTotal), currentTotal));
            } else {
                setStatus(getString(R.string.status_speaking));
            }
            return;
        }
        if (ReaderService.STATE_PAUSED.equals(currentState)) {
            readButton.setText(R.string.resume);
            if (currentTotal > 0) {
                setStatus(getString(R.string.status_progress_paused,
                        Math.min(currentIndex + 1, currentTotal), currentTotal));
            } else {
                setStatus(getString(R.string.status_paused));
            }
            return;
        }
        readButton.setText(R.string.speak);
        if (ReaderService.STATE_DONE.equals(currentState)) {
            setStatus(getString(R.string.status_done));
        } else if (ReaderService.STATE_IDLE.equals(currentState)) {
            setStatus(getString(R.string.status_ready));
        }
    }

    // -- Intents ----------------------------------------------------------
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (Intent.ACTION_MAIN.equals(action)) {
            // Opened from the launcher: start with an empty field.
            input.setText("");
            return;
        }
        CharSequence shared = null;
        if (Intent.ACTION_SEND.equals(action)) {
            shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            shared = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        }
        if (!TextUtils.isEmpty(shared)) {
            String text = shared.toString();
            input.setText(text);
            speak(text);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("draft", input.getText().toString());
    }

    // -- Lifecycle --------------------------------------------------------
    @Override
    protected void onStart() {
        super.onStart();
        registerStateReceiver();
        sendConfig();
    }

    @Override
    protected void onStop() {
        if (receiverRegistered && receiver != null) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    // -- Helpers ----------------------------------------------------------
    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                    REQUEST_NOTIFICATIONS);
        }
    }

    private void setStatus(String text) {
        if (status != null) {
            status.setText(text);
        }
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}

