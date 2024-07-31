package com.example.mingle;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Handler;
import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.example.mingle.adapter.ChatRecyclerAdapter;
import com.example.mingle.model.ChatMessageModel;
import com.example.mingle.model.ChatroomModel;
import com.example.mingle.model.UserModel;
import com.example.mingle.utils.AndroidUtil;
import com.example.mingle.utils.FirebaseUtil;
import com.firebase.ui.firestore.FirestoreRecyclerOptions;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.Query;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ChatActivity extends AppCompatActivity {

    UserModel otherUser;
    String chatroomId;
    ChatroomModel chatroomModel;
    ChatRecyclerAdapter adapter;

    EditText messageInput;
    ImageButton sendMessageBtn;
    ImageButton backBtn;
    TextView otherUsername;
    RecyclerView recyclerView;
    ImageView imageView;

    private final Handler handler = new Handler();
    int clickCount = 0;
    long startTime;
    long duration;
    static final int MAX_DURATION = 500; // Maximum duration between taps for it to be considered a double tap
    private static final long COUNTDOWN_INTERVAL = 100; // Adjusted for better timing
    protected static final int RESULT_SPEECH = 1;

    TextToSpeech t1;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Get UserModel
        otherUser = AndroidUtil.getUserModelFromIntent(getIntent());
        chatroomId = FirebaseUtil.getChatroomId(FirebaseUtil.currentUserId(), otherUser.getUserId());

        messageInput = findViewById(R.id.chat_message_input);
        sendMessageBtn = findViewById(R.id.message_send_btn);
        backBtn = findViewById(R.id.back_btn);
        otherUsername = findViewById(R.id.other_username);
        recyclerView = findViewById(R.id.chat_recycler_view);
        imageView = findViewById(R.id.profile_pic_image_view);

        FirebaseUtil.getOtherProfilePicStorageRef(otherUser.getUserId()).getDownloadUrl()
                .addOnCompleteListener(t -> {
                    if (t.isSuccessful()) {
                        Uri uri = t.getResult();
                        AndroidUtil.setProfilePic(this, uri, imageView);
                    }
                });

        backBtn.setOnClickListener(v -> onBackPressed());
        otherUsername.setText(otherUser.getUsername());

        sendMessageBtn.setOnClickListener(v -> {
            String message = messageInput.getText().toString().trim();
            if (!message.isEmpty()) {
                sendMessageToUser(message);
            }
        });

        t1 = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.ERROR) {
                t1.setLanguage(Locale.ENGLISH);
            }
        });

        // Event listener for double tap and long press
        recyclerView.setOnTouchListener(new View.OnTouchListener() {
            private boolean isMessageSent = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleRecyclerViewTouch(event);
            }

            private boolean handleRecyclerViewTouch(MotionEvent event) {
                int eventAction = event.getAction();
                switch (eventAction) {
                    case MotionEvent.ACTION_DOWN:
                        startTime = System.currentTimeMillis();
                        clickCount++;
                        startCountdown();
                        break;
                    case MotionEvent.ACTION_UP:
                        stopCountdown();
                        long time = System.currentTimeMillis() - startTime;
                        duration += time;
                        if (clickCount >= 2) {
                            if (duration >= MAX_DURATION) {
                                duration = 0;
                            }

                            // Logic for Activating Speech to Text
                            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US");
                            try {
                                startActivityForResult(intent, RESULT_SPEECH);
                                messageInput.setText("");
                            } catch (ActivityNotFoundException e) {
                                Toast.makeText(getApplicationContext(), "Your device doesn't support Speech to Text", Toast.LENGTH_SHORT).show();
                                e.printStackTrace();
                            }

                            clickCount = 0;
                            duration = 0;
                            isMessageSent = false;
                        }
                        break;
                }
                return true;
            }

            // Logic for sending message on long press
            private final Runnable countdownRunnable = new Runnable() {
                @Override
                public void run() {
                    long elapsedTime = System.currentTimeMillis() - startTime;
                    if (elapsedTime >= MAX_DURATION) {
                        handler.removeCallbacks(this);
                        String message = messageInput.getText().toString().trim();
                        if (!message.isEmpty() && !isMessageSent) {
                            sendMessageToUser(message);
                            isMessageSent = true;
                        }
                    } else {
                        handler.postDelayed(this, COUNTDOWN_INTERVAL);
                    }
                }
            };

            private void startCountdown() {
                handler.post(countdownRunnable);
            }

            private void stopCountdown() {
                handler.removeCallbacks(countdownRunnable);
            }
        });

        getOrCreateChatroomModel();
        setupChatRecyclerView();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESULT_SPEECH && resultCode == RESULT_OK && data != null) {
            ArrayList<String> text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (text != null && !text.isEmpty()) {
                String recognizedText = text.get(0);

                if (recognizedText.equalsIgnoreCase("Read me the last five messages in this conversation")) {
                    readLastFiveMessages();
                } else {
                    String message = recognizedText;
                    String recipient = otherUsername.getText().toString();
                    String part1 = "Sending the following message to " + recipient;
                    speakWithPause(part1, message);
                    messageInput.setText(message);
                }
            }
        }
    }

    private void speakWithPause(String part1, String part2) {
        t1.speak(part1, TextToSpeech.QUEUE_FLUSH, null, null);
        handler.postDelayed(() -> t1.speak(part2, TextToSpeech.QUEUE_ADD, null, null), 2000); // 2-second pause
    }

    private void readLastFiveMessages() {
        FirebaseUtil.getChatroomMessageReference(chatroomId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(5)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful() && task.getResult() != null) {
                        List<ChatMessageModel> messages = task.getResult().toObjects(ChatMessageModel.class);
                        StringBuilder messagesToRead = new StringBuilder();
                        for (int i = messages.size() - 1; i >= 0; i--) {
                            ChatMessageModel message = messages.get(i);
                            String senderName = message.getSenderId().equals(FirebaseUtil.currentUserId()) ? "You" : otherUser.getUsername();
                            messagesToRead.append(senderName).append(" said ").append(message.getMessage()).append(". ");
                        }
                        t1.speak("Reading the last five messages in this conversation." + messagesToRead.toString(), TextToSpeech.QUEUE_FLUSH, null, null);
                    }
                });
    }

    @Override
    public void onBackPressed() {
        Log.d("ChatActivity", "onBackPressed called");
        super.onBackPressed();
    }

    void setupChatRecyclerView() {
        Query query = FirebaseUtil.getChatroomMessageReference(chatroomId)
                .orderBy("timestamp", Query.Direction.DESCENDING);

        FirestoreRecyclerOptions<ChatMessageModel> options = new FirestoreRecyclerOptions.Builder<ChatMessageModel>()
                .setQuery(query, ChatMessageModel.class).build();

        adapter = new ChatRecyclerAdapter(options, getApplicationContext());
        LinearLayoutManager manager = new LinearLayoutManager(this);
        manager.setReverseLayout(true);
        recyclerView.setLayoutManager(manager);
        recyclerView.setAdapter(adapter);
        adapter.startListening();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                recyclerView.smoothScrollToPosition(0);
            }
        });
    }

    void sendMessageToUser(String message) {
        chatroomModel.setLastMessageTimestamp(Timestamp.now());
        chatroomModel.setLastMessageSenderId(FirebaseUtil.currentUserId());
        chatroomModel.setLastMessage(message);
        FirebaseUtil.getChatroomReference(chatroomId).set(chatroomModel);

        ChatMessageModel chatMessageModel = new ChatMessageModel(message, FirebaseUtil.currentUserId(), Timestamp.now());
        FirebaseUtil.getChatroomMessageReference(chatroomId).add(chatMessageModel)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        messageInput.setText("");
                        sendNotification(message);

                        // Read out "Message sent" using TTS
                        t1.speak("Message sent", TextToSpeech.QUEUE_FLUSH, null, null);

                        // Vibrate the phone
                        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                        if (vibrator != null && vibrator.hasVibrator()) {
                            vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)); // 500 ms vibration
                        }

                    }
                });
    }

    void getOrCreateChatroomModel() {
        FirebaseUtil.getChatroomReference(chatroomId).get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                chatroomModel = task.getResult().toObject(ChatroomModel.class);
                if (chatroomModel == null) {
                    // First time chat
                    chatroomModel = new ChatroomModel(
                            chatroomId,
                            Arrays.asList(FirebaseUtil.currentUserId(), otherUser.getUserId()),
                            Timestamp.now(),
                            ""
                    );
                    FirebaseUtil.getChatroomReference(chatroomId).set(chatroomModel);
                }
            }
        });
    }

    void sendNotification(String message) {
        FirebaseUtil.currentUserDetails().get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                UserModel currentUser = task.getResult().toObject(UserModel.class);
                try {
                    JSONObject jsonObject = new JSONObject();
                    JSONObject notificationObj = new JSONObject();
                    notificationObj.put("title", currentUser.getUsername());
                    notificationObj.put("body", message);
                    JSONObject dataObj = new JSONObject();
                    dataObj.put("userId", currentUser.getUserId());
                    jsonObject.put("notification", notificationObj);
                    jsonObject.put("data", dataObj);
                    jsonObject.put("to", otherUser.getFcmToken());
                    callApi(jsonObject);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    void callApi(JSONObject jsonObject) {
        MediaType JSON = MediaType.get("application/json; charset=utf-8");
        OkHttpClient client = new OkHttpClient();
        String url = "https://fcm.googleapis.com/v1/projects/backend-7270b/messages:send";
        RequestBody body = RequestBody.create(jsonObject.toString(), JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("Authorization", "Bearer YOUR_API_KEY")
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // Handle failure
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                // Handle success
            }
        });
    }
}
