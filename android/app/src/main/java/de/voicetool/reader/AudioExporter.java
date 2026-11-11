package de.voicetool.reader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renders text to an audio file with the system text-to-speech engine and
 * stores it in the public Music/EasyTTS folder.
 *
 * The engine renders WAV; the PCM is concatenated and encoded to AAC (M4A).
 * If encoding fails, the WAV is kept instead.
 */
final class AudioExporter {

    interface Progress {
        void onProgress(int done, int total);
    }

    private static final int CHUNK_CHARS = 500;
    private static final int BIT_RATE = 96000;

    private AudioExporter() {
    }

    static Uri export(Context context, String text, float rate, float pitch,
                      String voiceName, Progress progress) throws Exception {
        List<String> chunks = split(text);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("no text");
        }

        TextToSpeech tts = createEngine(context, rate, pitch, voiceName);
        File cache = new File(context.getCacheDir(), "export");
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();

        List<File> parts = new ArrayList<>();
        try {
            for (int index = 0; index < chunks.size(); index++) {
                File target = new File(cache, "part-" + index + ".wav");
                synthesize(tts, chunks.get(index), target, index);
                parts.add(target);
                if (progress != null) {
                    progress.onProgress(index + 1, chunks.size());
                }
            }
        } finally {
            tts.stop();
            tts.shutdown();
        }

        Wav combined = concat(parts);
        for (File part : parts) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        if (combined.pcm.length == 0) {
            throw new IOException("empty audio");
        }

        String stamp = new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(new Date());
        String base = "EasyTTS " + stamp;

        File m4a = new File(cache, "export.m4a");
        boolean encoded = false;
        try {
            encodeAac(combined, m4a);
            encoded = true;
        } catch (Exception ignored) {
            // Fall back to WAV below.
        }

        if (encoded) {
            Uri uri = store(context, m4a, base + ".m4a", "audio/mp4");
            //noinspection ResultOfMethodCallIgnored
            m4a.delete();
            return uri;
        }

        File wav = new File(cache, "export.wav");
        writeWav(combined, wav);
        Uri uri = store(context, wav, base + ".wav", "audio/wav");
        //noinspection ResultOfMethodCallIgnored
        wav.delete();
        return uri;
    }

    // -- Text-to-speech ---------------------------------------------------
    private static TextToSpeech createEngine(Context context, final float rate,
                                             final float pitch, final String voiceName)
            throws InterruptedException {
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicBoolean ok = new AtomicBoolean(false);
        final TextToSpeech[] holder = new TextToSpeech[1];

        holder[0] = new TextToSpeech(context, status -> {
            ok.set(status == TextToSpeech.SUCCESS);
            ready.countDown();
        });
        if (!ready.await(15, TimeUnit.SECONDS) || !ok.get()) {
            if (holder[0] != null) {
                holder[0].shutdown();
            }
            throw new IllegalStateException("text-to-speech not available");
        }
        TextToSpeech tts = holder[0];
        tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(Math.max(0.2f, rate));
        tts.setPitch(Math.max(0.2f, pitch));
        applyVoice(tts, voiceName);
        return tts;
    }

    private static void applyVoice(TextToSpeech tts, String voiceName) {
        if (voiceName == null || voiceName.isEmpty()) {
            return;
        }
        try {
            java.util.Set<android.speech.tts.Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            for (android.speech.tts.Voice voice : voices) {
                if (voiceName.equals(voice.getName())) {
                    tts.setVoice(voice);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Keep the engine default.
        }
    }

    private static void synthesize(TextToSpeech tts, String text, File target, int index)
            throws Exception {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean failed = new AtomicBoolean(false);
        final String utteranceId = "export-" + index;

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                if (utteranceId.equals(id)) {
                    done.countDown();
                }
            }

            @Override
            public void onError(String id) {
                if (utteranceId.equals(id)) {
                    failed.set(true);
                    done.countDown();
                }
            }
        });

        int result = tts.synthesizeToFile(text, new Bundle(), target, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            throw new IOException("synthesizeToFile failed: " + result);
        }
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new IOException("synthesis timed out");
        }
        if (failed.get() || !target.isFile() || target.length() == 0) {
            throw new IOException("synthesis failed");
        }
    }

    // -- WAV handling -----------------------------------------------------
    private static final class Wav {
        byte[] pcm = new byte[0];
        int sampleRate = 22050;
        int channels = 1;
        int bits = 16;
    }

    private static Wav concat(List<File> parts) throws IOException {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        Wav result = null;
        for (File part : parts) {
            Wav wav = readWav(part);
            if (result == null) {
                result = wav;
            } else if (wav.sampleRate != result.sampleRate
                    || wav.channels != result.channels
                    || wav.bits != result.bits) {
                continue; // Ignore a part with a different format.
            }
            pcm.write(wav.pcm);
        }
        if (result == null) {
            result = new Wav();
        }
        result.pcm = pcm.toByteArray();
        return result;
    }

    private static Wav readWav(File file) throws IOException {
        byte[] data = readAll(file);
        Wav wav = new Wav();
        if (data.length < 12
                || data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F') {
            throw new IOException("not a WAV file");
        }
        int pos = 12;
        byte[] pcm = null;
        while (pos + 8 <= data.length) {
            String id = new String(data, pos, 4, StandardCharsets.US_ASCII);
            int size = littleInt(data, pos + 4);
            int start = pos + 8;
            if (size < 0 || start + size > data.length) {
                size = data.length - start;
            }
            if ("fmt ".equals(id) && size >= 16) {
                wav.channels = littleShort(data, start + 2);
                wav.sampleRate = littleInt(data, start + 4);
                wav.bits = littleShort(data, start + 14);
            } else if ("data".equals(id)) {
                pcm = new byte[size];
                System.arraycopy(data, start, pcm, 0, size);
            }
            pos = start + size + (size & 1);
        }
        if (pcm == null) {
            throw new IOException("WAV has no data chunk");
        }
        wav.pcm = pcm;
        return wav;
    }

    private static void writeWav(Wav wav, File target) throws IOException {
        int blockAlign = Math.max(1, wav.channels * wav.bits / 8);
        int byteRate = wav.sampleRate * blockAlign;
        try (OutputStream out = new FileOutputStream(target)) {
            out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, 36 + wav.pcm.length);
            out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
            out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, 16);
            writeShort(out, 1);
            writeShort(out, wav.channels);
            writeInt(out, wav.sampleRate);
            writeInt(out, byteRate);
            writeShort(out, blockAlign);
            writeShort(out, wav.bits);
            out.write("data".getBytes(StandardCharsets.US_ASCII));
            writeInt(out, wav.pcm.length);
            out.write(wav.pcm);
        }
    }

    // -- AAC --------------------------------------------------------------
    private static void encodeAac(Wav wav, File target) throws IOException {
        if (wav.bits != 16) {
            throw new IOException("unsupported bit depth");
        }
        int channels = Math.max(1, wav.channels);
        int frameBytes = channels * 2;

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, wav.sampleRate, channels);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();

        MediaMuxer muxer = new MediaMuxer(target.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean muxerStarted = false;
        boolean inputDone = false;
        boolean outputDone = false;
        int offset = 0;
        long presentationUs = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        try {
            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10_000);
                    if (inIndex >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(inIndex);
                        if (buffer == null) {
                            throw new IOException("no input buffer");
                        }
                        buffer.clear();
                        int remaining = wav.pcm.length - offset;
                        if (remaining <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, presentationUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else if (remaining < frameBytes) {
                            // Trailing partial frame: drop it and finish.
                            offset = wav.pcm.length;
                        } else {
                            int size = Math.min(buffer.remaining(), remaining);
                            size -= size % frameBytes;
                            if (size <= 0) {
                                continue;
                            }
                            buffer.put(wav.pcm, offset, size);
                            codec.queueInputBuffer(inIndex, 0, size, presentationUs, 0);
                            offset += size;
                            presentationUs += (size / frameBytes) * 1_000_000L / wav.sampleRate;
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                    continue;
                }
                if (outIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outIndex);
                    if (buffer != null && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(track, buffer, info);
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Already stopped.
            }
            codec.release();
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } finally {
                muxer.release();
            }
        }
        if (!muxerStarted || target.length() == 0) {
            throw new IOException("muxer produced no audio");
        }
    }

    // -- Storage ----------------------------------------------------------
    private static Uri store(Context context, File source, String name, String mime)
            throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
            values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MUSIC + "/EasyTTS");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);

            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("could not create media entry");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("could not open media entry");
                }
                copy(source, out);
            } catch (IOException exc) {
                resolver.delete(uri, null, null);
                throw exc;
            }
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return uri;
        }

        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), "EasyTTS");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File target = new File(dir, name);
        try (OutputStream out = new FileOutputStream(target)) {
            copy(source, out);
        }
        MediaScannerConnection.scanFile(context,
                new String[]{target.getAbsolutePath()}, new String[]{mime}, null);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DATA, target.getAbsolutePath());
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
        Uri uri = context.getContentResolver().insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        return uri != null ? uri : Uri.fromFile(target);
    }

    // -- Helpers ----------------------------------------------------------
    private static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.replaceAll("[ \\t]+", " ").trim();
        while (remaining.length() > CHUNK_CHARS) {
            int cut = Math.max(
                    remaining.lastIndexOf(". ", CHUNK_CHARS),
                    Math.max(remaining.lastIndexOf("? ", CHUNK_CHARS),
                            remaining.lastIndexOf("! ", CHUNK_CHARS)));
            if (cut < CHUNK_CHARS / 2) {
                cut = remaining.lastIndexOf(' ', CHUNK_CHARS);
            }
            if (cut <= 0) {
                cut = CHUNK_CHARS;
            }
            chunks.add(remaining.substring(0, cut + 1).trim());
            remaining = remaining.substring(cut + 1).trim();
        }
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        return chunks;
    }

    private static byte[] readAll(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void copy(File source, OutputStream out) throws IOException {
        try (InputStream in = new FileInputStream(source)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static int littleInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static int littleShort(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
