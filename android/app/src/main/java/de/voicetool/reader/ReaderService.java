package de.voicetool.reader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Background reading: keeps text-to-speech alive when the app is not in the
 * foreground and offers pause/stop in the notification.
 *
 * The activity controls the service via intents; status updates come back as
 * a broadcast (ACTION_STATE).
 */
public class ReaderService extends Service implements TextToSpeech.OnInitListener {

    public static final String ACTION_SPEAK = "de.voicetool.reader.SPEAK";
    public static final String ACTION_READ = "de.voicetool.reader.READ";
    public static final String ACTION_TOGGLE = "de.voicetool.reader.TOGGLE";
    public static final String ACTION_STOP = "de.voicetool.reader.STOP";
    public static final String ACTION_CONFIG = "de.voicetool.reader.CONFIG";
    public static final String ACTION_STATE = "de.voicetool.reader.STATE";

    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_RATE = "rate";
    public static final String EXTRA_PITCH = "pitch";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_VOICE = "voice";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_VOICE_LABELS = "voiceLabels";
    public static final String EXTRA_VOICE_IDS = "voiceIds";
    public static final String EXTRA_SUGGESTED_VOICE = "suggestedVoice";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_SPEAKING = "speaking";
    public static final String STATE_PAUSED = "paused";
    public static final String STATE_DONE = "done";
    public static final String STATE_ERROR = "error";

    private static final String CHANNEL_ID = "reader";
    private static final int NOTIFICATION_ID = 4711;
    private static final int MAX_CHUNK_CHARS = 400;

    private TextToSpeech tts;
    private boolean ready;
    private boolean paused;
    private boolean foreground;
    private String state = STATE_IDLE;
    private String pendingText;
    private String currentUtterance = "";

    private final List<String> chunks = new ArrayList<>();
    private int index;

    private float rate = 1f;
    private float pitch = 1f;
    private float volume = 1f;
    private String voiceName = "";

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;

    // -- Lifecycle -------------------------------------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        tts = new TextToSpeech(this, this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_SPEAK.equals(action)) {
                applyConfig(intent);
                String text = intent.getStringExtra(EXTRA_TEXT);
                if (!TextUtils.isEmpty(text)) {
                    speak(text);
                }
            } else if (ACTION_READ.equals(action)) {
                // The service decides: pause/resume when active, otherwise start.
                applyConfig(intent);
                if (paused || (tts != null && tts.isSpeaking())) {
                    toggle();
                } else {
                    String text = intent.getStringExtra(EXTRA_TEXT);
                    if (!TextUtils.isEmpty(text)) {
                        speak(text);
                    }
                }
            } else if (ACTION_TOGGLE.equals(action)) {
                toggle();
            } else if (ACTION_STOP.equals(action)) {
                stopAll();
            } else if (ACTION_CONFIG.equals(action)) {
                applyConfig(intent);
                broadcastState();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        abandonFocus();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    // -- TextToSpeech ----------------------------------------------------
    @Override
    public void onInit(int code) {
        if (code != TextToSpeech.SUCCESS) {
            state = STATE_ERROR;
            broadcastState();
            return;
        }
        ready = true;
        int language = tts.setLanguage(Locale.getDefault());
        if (language == TextToSpeech.LANG_MISSING_DATA
                || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.US);
        }
        applyVoice();
        applyRatePitch();
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                if (!utteranceId.equals(currentUtterance)) {
                    return;
                }
                state = STATE_SPEAKING;
                broadcastState();
                updateNotification();
            }

            @Override
            public void onDone(String utteranceId) {
                if (!utteranceId.equals(currentUtterance)) {
                    return;
                }
                nextChunk();
            }

            @Override
            public void onError(String utteranceId) {
                if (!utteranceId.equals(currentUtterance)) {
                    return;
                }
                state = STATE_ERROR;
                finishForeground();
                broadcastState();
            }

            @Override
            public void onStop(String utteranceId, boolean interrupted) {
                // Intentionally empty: pause/stop is handled by the service itself.
            }
        });
        broadcastState();
        if (pendingText != null) {
            String text = pendingText;
            pendingText = null;
            speak(text);
        }
    }

    // -- Configuration ---------------------------------------------------
    private void applyConfig(Intent intent) {
        if (intent.hasExtra(EXTRA_RATE)) {
            rate = intent.getFloatExtra(EXTRA_RATE, 1f);
        }
        if (intent.hasExtra(EXTRA_PITCH)) {
            pitch = intent.getFloatExtra(EXTRA_PITCH, 1f);
        }
        if (intent.hasExtra(EXTRA_VOLUME)) {
            volume = Math.max(0f, Math.min(1f, intent.getFloatExtra(EXTRA_VOLUME, 1f)));
        }
        if (intent.hasExtra(EXTRA_VOICE)) {
            String name = intent.getStringExtra(EXTRA_VOICE);
            voiceName = name == null ? "" : name;
        }
        applyVoice();
        applyRatePitch();
    }

    private void applyRatePitch() {
        if (!ready) {
            return;
        }
        tts.setSpeechRate(Math.max(0.2f, rate));
        tts.setPitch(Math.max(0.2f, pitch));
    }

    private void applyVoice() {
        if (!ready || voiceName == null || voiceName.isEmpty()) {
            return;
        }
        try {
            java.util.Set<Voice> all = tts.getVoices();
            if (all == null) {
                return;
            }
            for (Voice voice : all) {
                if (voiceName.equals(voice.getName())) {
                    tts.setVoice(voice);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Engine without a voice list: keep the system voice.
        }
    }

    // -- Playback --------------------------------------------------------
    private void speak(String text) {
        if (!ready) {
            pendingText = text;
            return;
        }
        buildChunks(text);
        if (chunks.isEmpty()) {
            return;
        }
        paused = false;
        index = 0;
        requestFocus();
        startForeground();
        speakChunk();
    }

    private void buildChunks(String text) {
        chunks.clear();
        String remaining = text.replaceAll("[ \\t]+", " ").trim();
        while (remaining.length() > MAX_CHUNK_CHARS) {
            int cut = Math.max(
                    remaining.lastIndexOf(". ", MAX_CHUNK_CHARS),
                    Math.max(remaining.lastIndexOf("? ", MAX_CHUNK_CHARS),
                            remaining.lastIndexOf("! ", MAX_CHUNK_CHARS)));
            if (cut < MAX_CHUNK_CHARS / 2) {
                cut = remaining.lastIndexOf(' ', MAX_CHUNK_CHARS);
            }
            if (cut <= 0) {
                cut = MAX_CHUNK_CHARS;
            }
            chunks.add(remaining.substring(0, cut + 1).trim());
            remaining = remaining.substring(cut + 1).trim();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
    }

    private void speakChunk() {
        if (index >= chunks.size()) {
            finishSpeech();
            return;
        }
        currentUtterance = "vt-" + index + "-" + System.currentTimeMillis();
        Bundle params = new Bundle();
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
        tts.speak(chunks.get(index), TextToSpeech.QUEUE_FLUSH, params, currentUtterance);
        state = STATE_SPEAKING;
        broadcastState();
        updateNotification();
    }

    private void nextChunk() {
        if (paused) {
            return;
        }
        index++;
        if (index < chunks.size()) {
            speakChunk();
        } else {
            finishSpeech();
        }
    }

    private void finishSpeech() {
        paused = false;
        state = STATE_DONE;
        abandonFocus();
        finishForeground();
        broadcastState();
    }

    private void toggle() {
        if (!ready) {
            return;
        }
        if (paused) {
            paused = false;
            requestFocus();
            speakChunk();
            return;
        }
        if (tts.isSpeaking() || foreground) {
            paused = true;
            tts.stop();
            state = STATE_PAUSED;
            abandonFocus();
            updateNotification();
            broadcastState();
        }
    }

    private void stopAll() {
        paused = false;
        index = chunks.size();
        if (tts != null) {
            tts.stop();
        }
        state = STATE_IDLE;
        abandonFocus();
        finishForeground();
        broadcastState();
    }

    // -- Audio focus -----------------------------------------------------
    private void requestFocus() {
        if (audioManager == null) {
            return;
        }
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS
                                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            if (paused) {
                                return;
                            }
                            paused = true;
                            if (tts != null) {
                                tts.stop();
                            }
                            state = STATE_PAUSED;
                            updateNotification();
                            broadcastState();
                        }
                    })
                    .build();
        }
        audioManager.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (audioManager != null && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
        }
    }

    // -- Notification ----------------------------------------------------
    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foreground = true;
    }

    private void finishForeground() {
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void updateNotification() {
        if (!foreground) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(
                this, 10, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String label = paused ? getString(R.string.resume) : getString(R.string.pause);
        PendingIntent toggleIntent = PendingIntent.getService(
                this, 11,
                new Intent(this, ReaderService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stopIntent = PendingIntent.getService(
                this, 12,
                new Intent(this, ReaderService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String text = getString(R.string.status_progress, Math.min(index + 1, chunks.size()), chunks.size());
        if (paused) {
            text = getString(R.string.state_paused_short) + " " + text;
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_reader)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, paused
                                ? android.R.drawable.ic_media_play
                                : android.R.drawable.ic_media_pause),
                        label, toggleIntent).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        getString(R.string.stop), stopIntent).build());
        return builder.build();
    }

    // -- Status to the activity ------------------------------------------
    private void broadcastState() {
        Intent out = new Intent(ACTION_STATE).setPackage(getPackageName());
        out.putExtra(EXTRA_STATE, state);
        out.putExtra(EXTRA_INDEX, index);
        out.putExtra(EXTRA_TOTAL, chunks.size());

        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        String suggested = "";
        int bestScore = Integer.MIN_VALUE;
        if (ready) {
            try {
                java.util.Set<Voice> all = tts.getVoices();
                if (all != null) {
                    for (Voice voice : all) {
                        labels.add(voice.getLocale().getDisplayName() + " - " + voice.getName());
                        ids.add(voice.getName());

                        int score = scoreVoice(voice);
                        if (score > bestScore) {
                            bestScore = score;
                            suggested = voice.getName();
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // No voice list available.
            }
        }
        out.putExtra(EXTRA_VOICE_LABELS, labels.toArray(new String[0]));
        out.putExtra(EXTRA_VOICE_IDS, ids.toArray(new String[0]));
        out.putExtra(EXTRA_SUGGESTED_VOICE, suggested);
        sendBroadcast(out);
    }

    /** Scores voices for automatic selection (device language first). */
    private int scoreVoice(Voice voice) {
        Locale device = Locale.getDefault();
        if (!device.getLanguage().equals(voice.getLocale().getLanguage())) {
            return Integer.MIN_VALUE + 1;
        }
        java.util.Set<String> features = voice.getFeatures();
        if (features != null && features.contains("notInstalled")) {
            return Integer.MIN_VALUE + 1;
        }
        int score = voice.getQuality();                 // 300 (very low) .. 500 (very high)
        if (!device.getCountry().isEmpty()
                && device.getCountry().equalsIgnoreCase(voice.getLocale().getCountry())) {
            score += 20;
        }
        // Prefer local voices: they work without a data connection.
        if (!voice.isNetworkConnectionRequired()) {
            score += 15;
        }
        if (features != null && features.contains("network")) {
            score += 5;                                 // neural/cloud voices usually sound better
        }
        return score;
    }
}

