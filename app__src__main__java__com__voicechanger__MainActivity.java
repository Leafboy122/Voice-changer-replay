package com.voicechanger;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE = 4096;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;
    private Thread recordingThread;
    private Thread playbackThread;
    private boolean isRecording = false;
    private boolean isPlaying = false;

    private VoiceEffectProcessor effectProcessor;
    private Button recordButton, stopButton, playButton;
    private Spinner effectSpinner;
    private SeekBar pitchSeekBar, speedSeekBar, volumeSeekBar;
    private TextView statusText;

    private List<Short> lastRecording = new ArrayList<>();
    private File lastRecordingFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestPermissions();
        initializeUI();
        setupEffectSpinner();
        effectProcessor = new VoiceEffectProcessor(SAMPLE_RATE);
        lastRecordingFile = new File(getCacheDir(), "last_voice.pcm");
    }

    private void initializeUI() {
        recordButton = findViewById(R.id.btn_start);
        stopButton = findViewById(R.id.btn_stop);
        playButton = findViewById(R.id.btn_play);
        effectSpinner = findViewById(R.id.spinner_effects);
        pitchSeekBar = findViewById(R.id.seekbar_pitch);
        speedSeekBar = findViewById(R.id.seekbar_speed);
        volumeSeekBar = findViewById(R.id.seekbar_volume);
        statusText = findViewById(R.id.tv_status);

        recordButton.setText("● RECORD");
        stopButton.setText("⏹ STOP");
        playButton.setText("▶ PLAY LAST");

        recordButton.setOnClickListener(v -> startRecording());
        stopButton.setOnClickListener(v -> stopRecording());
        playButton.setOnClickListener(v -> playLastRecording());

        pitchSeekBar.setMax(100);
        pitchSeekBar.setProgress(50);
        pitchSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                effectProcessor.setPitch(0.5f + (progress / 100f) * 2f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        speedSeekBar.setMax(100);
        speedSeekBar.setProgress(50);
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                effectProcessor.setSpeed(0.5f + (progress / 100f) * 2f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        volumeSeekBar.setMax(100);
        volumeSeekBar.setProgress(70);
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                effectProcessor.setVolume(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupEffectSpinner() {
        String[] effects = {"Normal", "Robot", "Helium", "Deep Voice", "Echo", "Reverb"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, effects);
        effectSpinner.setAdapter(adapter);
        effectSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                effectProcessor.setEffect(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void requestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required", Toast.LENGTH_LONG).show();
        }
    }

    private void startRecording() {
        if (isRecording || isPlaying) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions();
            return;
        }

        try {
            lastRecording.clear();
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, Math.max(minBufferSize, BUFFER_SIZE * 2));
            audioRecord.startRecording();
            isRecording = true;

            recordingThread = new Thread(this::recordAndProcessAudio);
            recordingThread.start();

            statusText.setText("🎙️ RECORDING...");
            statusText.setTextColor(getResources().getColor(R.color.accent));
            recordButton.setEnabled(false);
            stopButton.setEnabled(true);
            playButton.setEnabled(false);
            Toast.makeText(this, "Recording started - Speak now!", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void recordAndProcessAudio() {
        short[] audioBuffer = new short[BUFFER_SIZE];
        while (isRecording) {
            int readSize = audioRecord.read(audioBuffer, 0, BUFFER_SIZE);
            if (readSize > 0) {
                short[] processed = effectProcessor.processAudio(audioBuffer, readSize);
                for (int i = 0; i < readSize; i++) {
                    lastRecording.add(processed[i]);
                }
            }
        }
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;

        if (audioRecord != null) {
            try { audioRecord.stop(); audioRecord.release(); } catch (Exception e) {}
            audioRecord = null;
        }
        try { if (recordingThread != null) recordingThread.join(1000); } catch (Exception e) {}

        try {
            FileOutputStream fos = new FileOutputStream(lastRecordingFile);
            for (short sample : lastRecording) {
                fos.write(sample & 0xFF);
                fos.write((sample >> 8) & 0xFF);
            }
            fos.close();
        } catch (IOException e) { e.printStackTrace(); }

        int seconds = lastRecording.size() / SAMPLE_RATE;
        statusText.setText("✅ Saved! (" + seconds + "s) - Press PLAY LAST");
        statusText.setTextColor(getResources().getColor(R.color.success));
        recordButton.setEnabled(true);
        stopButton.setEnabled(false);
        playButton.setEnabled(true);
        Toast.makeText(this, "Recording saved!", Toast.LENGTH_SHORT).show();
    }

    private void playLastRecording() {
        if (isRecording || isPlaying) return;
        if (lastRecording.isEmpty() && !lastRecordingFile.exists()) {
            Toast.makeText(this, "No recording yet. Record something first!", Toast.LENGTH_SHORT).show();
            return;
        }

        isPlaying = true;
        statusText.setText("🔊 PLAYING LAST RECORDING...");
        statusText.setTextColor(getResources().getColor(R.color.primary));
        recordButton.setEnabled(false);
        stopButton.setEnabled(false);
        playButton.setEnabled(false);

        playbackThread = new Thread(() -> {
            try {
                short[] data;
                if (!lastRecording.isEmpty()) {
                    data = new short[lastRecording.size()];
                    for (int i = 0; i < lastRecording.size(); i++) data[i] = lastRecording.get(i);
                } else {
                    FileInputStream fis = new FileInputStream(lastRecordingFile);
                    byte[] bytes = new byte[(int) lastRecordingFile.length()];
                    fis.read(bytes);
                    fis.close();
                    data = new short[bytes.length / 2];
                    for (int i = 0; i < data.length; i++) {
                        data[i] = (short) ((bytes[i*2] & 0xFF) | (bytes[i*2+1] << 8));
                    }
                }

                int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT);
                audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AUDIO_FORMAT, Math.max(minBuffer, BUFFER_SIZE*4), AudioTrack.MODE_STREAM);
                audioTrack.play();
                audioTrack.write(data, 0, data.length);
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isPlaying = false;
                runOnUiThread(() -> {
                    statusText.setText("Ready - Record new or Play again");
                    statusText.setTextColor(getResources().getColor(R.color.text_primary));
                    recordButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    playButton.setEnabled(true);
                });
            }
        });
        playbackThread.start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRecording = false;
        isPlaying = false;
        if (audioRecord != null) { audioRecord.release(); audioRecord = null; }
        if (audioTrack != null) { audioTrack.release(); audioTrack = null; }
    }
}
