package de.voicetool.reader;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

    private EditText input;
    private SeekBar rateBar;
    private SeekBar pitchBar;
    private Spinner voiceSpinner;
    private TextView status;
    private Button pauseButton;

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
        voiceSpinner = findViewById(R.id.voice);
        status = findViewById(R.id.status);
        pauseButton = findViewById(R.id.pause);

        input.setText(prefs.getString("lastText", ""));
        rateBar.setProgress(prefs.getInt("rate", 100));
        pitchBar.setProgress(prefs.getInt("pitch", 100));
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

        Button speakButton = findViewById(R.id.speak);
        Button stopButton = findViewById(R.id.stop);
        speakButton.setOnClickListener(v -> speakInput());
        pauseButton.setOnClickListener(v -> sendAction(ReaderService.ACTION_TOGGLE));
        stopButton.setOnClickListener(v -> {
            sendAction(ReaderService.ACTION_STOP);
            setStatus(getString(R.string.status_stopped));
        });

        if (savedVoice != null && !savedVoice.isEmpty()) {
            // Selection is applied once the service sends the voice list.
            prefs.edit().putString("voice", savedVoice).apply();
        }
        askNotificationPermission();
        handleIntent(getIntent());
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
                .putExtra(ReaderService.EXTRA_VOICE, prefs.getString("voice", ""));
        startService(intent);
    }

    private void speakInput() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.hint_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs.edit().putString("lastText", text).apply();
        speak(text);
    }

    private void speak(String text) {
        Intent intent = new Intent(this, ReaderService.class)
                .setAction(ReaderService.ACTION_SPEAK)
                .putExtra(ReaderService.EXTRA_TEXT, text)
                .putExtra(ReaderService.EXTRA_RATE, rateBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_PITCH, pitchBar.getProgress() / 100f)
                .putExtra(ReaderService.EXTRA_VOICE, prefs.getString("voice", ""));
        startService(intent);
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
            pauseButton.setText(R.string.pause);
            setStatus(getString(R.string.status_error));
            return;
        }
        if (ReaderService.STATE_SPEAKING.equals(currentState)) {
            pauseButton.setText(R.string.pause);
            if (currentTotal > 0) {
                setStatus(getString(R.string.status_progress_speaking,
                        Math.min(currentIndex + 1, currentTotal), currentTotal));
            } else {
                setStatus(getString(R.string.status_speaking));
            }
            return;
        }
        if (ReaderService.STATE_PAUSED.equals(currentState)) {
            pauseButton.setText(R.string.resume);
            if (currentTotal > 0) {
                setStatus(getString(R.string.status_progress_paused,
                        Math.min(currentIndex + 1, currentTotal), currentTotal));
            } else {
                setStatus(getString(R.string.status_paused));
            }
            return;
        }
        pauseButton.setText(R.string.pause);
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
        CharSequence shared = null;
        String action = intent.getAction();
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
    protected void onPause() {
        super.onPause();
        prefs.edit().putString("lastText", input.getText().toString()).apply();
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

