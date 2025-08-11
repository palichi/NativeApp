package com.example.nativeapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var textView: TextView
    private lateinit var button: Button
    private lateinit var textToSpeech: TextToSpeech

    private val conversationHistory = mutableListOf<Map<String, String>>()
    private var isFirstInput = true

    private val REQUEST_CODE_SPEECH_INPUT = 100
    private val RECORD_AUDIO_PERMISSION_CODE = 1

    private lateinit var chatAdapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(chatMessages)
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        textView = findViewById(R.id.responseText)
        button = findViewById(R.id.startButton)

        val resetButton = findViewById<Button>(R.id.resetButton)
        resetButton.setOnClickListener {
            resetConversation()
        }
        textToSpeech = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                textToSpeech.language = Locale.US
            }else {
                Toast.makeText(this, "TTS 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }

        button.setOnClickListener {
            checkMicPermissionAndStart()
        }
    }
    private fun resetConversation() {
        conversationHistory.clear() // OpenAI용 기록 초기화
        chatMessages.clear()        // RecyclerView 리스트 초기화
        chatAdapter.notifyDataSetChanged() // 리스트 새로고침
        isFirstInput = true
        textView.text = ""

        runOnUiThread {
            textView.text = "응답이 여기에 표시됩니다" // 초기 응답창 텍스트로 복구
        }
    }
    private fun checkMicPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            startSpeechRecognition()
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("en", "US"))
            putExtra(RecognizerIntent.EXTRA_PROMPT, "상황을 말해주세요")
        }
        startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == Activity.RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val result = results?.get(0)
            if (result == null) {
                Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                return
            }

            if (isFirstInput) {
                handleFirstSituation(result)
            } else {
                continueConversation(result)
            }
        }
    }

    private fun handleFirstSituation(situation: String) {
        isFirstInput = false
        conversationHistory.clear()

        addMessage("system", "You are an English teacher. Respond naturally in English based on the situation.")
        addMessage("user",   "Situation: $situation")
        addMessage("user", "Start the conversation. Say one sentence in English.")

        Log.d("conversationHistory", "API Key: $conversationHistory")

        sendToOpenAI(conversationHistory) { reply ->
            addMessage("assistant", reply)
            speakAndDisplay(reply)
        }
    }

    private fun continueConversation(userInput: String) {

        addMessage("user", userInput)
        sendToOpenAI(conversationHistory) { reply ->
            addMessage("assistant", reply)
            speakAndDisplay(reply)
        }
    }

    private fun addMessage(role: String, content: String) {
        conversationHistory.add(mapOf("role" to role, "content" to content))
        chatMessages.add(ChatMessage(role, content))                          // RecyclerView용

        runOnUiThread {
            chatAdapter.notifyItemInserted(chatMessages.size - 1)
        }
    }

    private fun speakAndDisplay(text: String) {
        textView.text = "AI: $text"
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun sendToOpenAI(messages: List<Map<String, String>>, callback: (String) -> Unit) {
        val apiKey =BuildConfig.OPENAI_API_KEY
        val client = OkHttpClient()
        val url = "https://api.openai.com/v1/chat/completions"
        // ✅ JSON 변환
        val jsonMessages = JSONArray()
        for (msg in messages) {
            val jsonMsg = JSONObject()
            jsonMsg.put("role", msg["role"])
            jsonMsg.put("content", msg["content"])
            jsonMessages.put(jsonMsg)
        }
        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", jsonMessages)
        }

        val body: RequestBody = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        // 로그 출력
        Log.d("HTTP_REQUEST", "➡️ Request: ${request.method} ${request.url}")
        Log.d("HTTP_REQUEST", "📋 Headers:")
        for ((name, value) in request.headers) {
            Log.d("HTTP_REQUEST", "$name: $value")
        }
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val bodyString = buffer.readUtf8()
        Log.d("HTTP_REQUEST", "📝 Body: $bodyString")

        Thread {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("Unexpected response $response")

                val bodyStr = response.body?.string() ?: ""
                val reply = JSONObject(bodyStr).getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")

                runOnUiThread { callback(reply) }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    textView.text = "Error: ${e.message}"
                    Toast.makeText(this, "API 호출 중 오류 발생", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition()
            } else {
                Toast.makeText(this, "음성 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
