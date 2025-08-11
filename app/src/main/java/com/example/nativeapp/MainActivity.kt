package com.example.nativeapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/**
 * MainActivity.kt
 *
 * 기능 요약:
 * - 사용자가 말하면(음성인식) 선택한 언어로 인식하고,
 * - 인식된 텍스트를 OpenAI(Chat Completions)로 보내 응답을 받아 화면에 표시하고 TTS로 읽음.
 * - 언어(English, 한국어 등)를 Spinner로 선택할 수 있음.
 *
 * 주의:
 * - OpenAI API 키는 BuildConfig.OPENAI_API_KEY에 저장되어 있어야 합니다.
 *   (gradle에 buildConfigField로 넣거나 안전한 방법으로 관리하세요)
 * - 네트워크 호출은 별도 스레드에서 수행합니다(메인 스레드 차단 금지).
 * - 이 예제는 간단한 샘플이며, 에러처리/보안(키 노출) 강화가 필요합니다.
 */
class MainActivity : AppCompatActivity() {

    // UI 요소들
    private lateinit var textView: TextView           // AI 응답을 보여주는 TextView
    private lateinit var button: Button               // 음성 인식 시작 버튼
    private lateinit var resetButton: Button          // 대화 초기화 버튼
    private lateinit var languageSpinner: Spinner     // 언어 선택 Spinner
    private lateinit var textToSpeech: TextToSpeech   // TTS 엔진

    // OpenAI용 대화 기록 (role: "system"/"user"/"assistant", content: 실제 텍스트)
    // Chat API는 이전 대화를 보내 컨텍스트를 유지하기 때문에 리스트로 관리함
    private val conversationHistory = mutableListOf<Map<String, String>>()

    // 첫 입력인지 여부 (첫 입력은 '상황 설정' -> 시스템/유저 초기 메시지 구성)
    private var isFirstInput = true

    // Request 코드들 (음성인식, 권한 등)
    private val REQUEST_CODE_SPEECH_INPUT = 100
    private val RECORD_AUDIO_PERMISSION_CODE = 1

    // RecyclerView용 어댑터와 메시지 리스트 (채팅처럼 보여주기 위함)
    private lateinit var chatAdapter: ChatAdapter
    private val chatMessages = mutableListOf<ChatMessage>()

    // 현재 선택된 언어 (기본: 영어 미국)
    // Locale 객체를 사용하면 TTS에는 바로 넣을 수 있고, 음성인식에는 문자열로 변환해 사용함
    private var selectedLocale: Locale = Locale("en", "US")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // activity_main.xml 레이아웃을 사용한다고 가정
        setContentView(R.layout.activity_main)

        // -----------------------------
        // RecyclerView 초기화 (채팅 기록을 보여줌)
        // -----------------------------
        val recyclerView = findViewById<RecyclerView>(R.id.chatRecyclerView)
        chatAdapter = ChatAdapter(chatMessages) // ChatAdapter는 ChatMessage 모델을 받도록 구현되어 있어야 함
        recyclerView.adapter = chatAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)

        // -----------------------------
        // 위젯 연결 (findViewById)
        // -----------------------------
        textView = findViewById(R.id.responseText)
        button = findViewById(R.id.startButton)
        resetButton = findViewById(R.id.resetButton)
        languageSpinner = findViewById(R.id.languageSpinner) // activity_main.xml에 Spinner 추가 필요

        // -----------------------------
        // Spinner(언어 선택) 초기화
        // - 보여질 텍스트 목록과 실제 Locale 값을 함께 관리
        // -----------------------------
        val languages = listOf(
            "English (US)" to Locale("en", "US"),
            "한국어 (KR)" to Locale("ko", "KR"),
            "日本語 (JP)" to Locale("ja", "JP"),
            "中文(简体)" to Locale("zh", "CN")
        )
        // Spinner에 표시할 문자열 어댑터 생성
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.first })
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        languageSpinner.adapter = spinnerAdapter

        // Spinner 선택 리스너: 사용자가 언어를 바꾸면 selectedLocale을 변경하고 TTS 언어도 설정
        languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedLocale = languages[position].second
                // TTS 초기화가 끝난 상태라면 언어를 즉시 변경
                if (::textToSpeech.isInitialized) {
                    val res = textToSpeech.setLanguage(selectedLocale)
                    // 언어 지원 여부 확인 (예: 일부 기기는 특정 로케일 데이터가 없을 수 있음)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Toast.makeText(this@MainActivity, "선택한 언어가 TTS에서 지원되지 않을 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                // 아무 것도 선택되지 않았을 때는 기본 유지
            }
        }

        // -----------------------------
        // TextToSpeech 초기화
        // - 비동기 초기화이므로 성공 콜백에서 언어를 설정
        // -----------------------------
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 초기 선택된 로케일을 TTS에 적용
                val result = textToSpeech.setLanguage(selectedLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 필요시 사용자에게 언어 데이터 설치 안내 필요
                    Toast.makeText(this, "TTS 언어 데이터가 없거나 지원하지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "TTS 초기화 실패", Toast.LENGTH_SHORT).show()
            }
        }

        // -----------------------------
        // 버튼 클릭 이벤트 연결
        // - startButton: 음성 인식 시작 (권한 확인 포함)
        // - resetButton: 대화 초기화
        // -----------------------------
        button.setOnClickListener { checkMicPermissionAndStart() }
        resetButton.setOnClickListener { resetConversation() }

        // (선택) 앱 시작 시 안내 텍스트
        textView.text = "언어를 선택한 뒤 마이크 버튼을 눌러 상황(또는 말을) 입력하세요."
    }

    /**
     * 대화 기록 및 UI를 초기 상태로 되돌리는 함수
     */
    private fun resetConversation() {
        conversationHistory.clear()      // OpenAI에 보낼 대화 기록 초기화
        chatMessages.clear()             // RecyclerView에 보여질 메시지 리스트 초기화
        chatAdapter.notifyDataSetChanged()// 화면 갱신
        isFirstInput = true              // 다음 입력은 '첫 입력'으로 처리
        textView.text = "응답이 여기에 표시됩니다"
    }

    /**
     * 마이크 권한 체크 후 음성 인식 시작
     * - 권한이 없으면 사용자에게 권한 요청 팝업을 띄움
     */
    private fun checkMicPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 권한 요청 (사용자가 허용/거부)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            // 권한이 이미 있을 때 바로 음성 인식 시작
            startSpeechRecognition()
        }
    }

    /**
     * 음성 인식 인텐트를 생성하고 시작하는 함수
     * RecognizerIntent.EXTRA_LANGUAGE 에는 "en-US" 같은 언어 태그를 문자열로 넣어야 안정적임.
     */
    private fun startSpeechRecognition() {
        // RecognizerIntent 사용 시, EXTRA_LANGUAGE_MODEL, EXTRA_LANGUAGE, EXTRA_PROMPT 등을 설정할 수 있음
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // 자연어 모델 (자유발화)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)

            // 선택된 Locale을 언어 태그 문자열로 변환하여 넣음 (예: "en-US", "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeToLanguageTag(selectedLocale))

            // 마이크 화면에 표시될 안내문구
            putExtra(RecognizerIntent.EXTRA_PROMPT, "말씀해주세요")
            // 최대 결과 개수 등 추가 옵션 설정 가능 (생략)
        }

        // startActivityForResult로 결과을 받음 (비동기)
        startActivityForResult(intent, REQUEST_CODE_SPEECH_INPUT)
    }

    /**
     * Locale -> 언어 태그 변환 유틸
     * API 레벨에 따라 toLanguageTag()가 없을 수 있으므로 안전하게 처리
     */
    private fun localeToLanguageTag(locale: Locale): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            locale.toLanguageTag() // "en-US" 형태
        } else {
            // 구형 기기용 폴백 ("en-US" 형태로 직접 구성)
            val country = if (locale.country.isNullOrEmpty()) "" else "-${locale.country}"
            "${locale.language}$country"
        }
    }

    /**
     * 음성 인식 결과 처리
     * - requestCode로 어떤 액티비티 결과인지 확인
     * - isFirstInput 여부에 따라 '상황 입력' 또는 '대화 계속' 처리
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SPEECH_INPUT && resultCode == Activity.RESULT_OK) {
            // RecognizerIntent.EXTRA_RESULTS로부터 후보 문장 리스트를 가져옴
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val result = results?.getOrNull(0) // 가장 유력한 결과 하나 사용
            if (result == null) {
                Toast.makeText(this, "음성 인식 결과가 없습니다.", Toast.LENGTH_SHORT).show()
                return
            }

            // 첫 입력이면 상황 설정 처리, 아니면 대화 이어가기
            if (isFirstInput) {
                handleFirstSituation(result)
            } else {
                continueConversation(result)
            }
        }
    }

    /**
     * 첫 상황 입력을 처리하는 함수
     * - 시스템 메시지(역할)와 사용자 메시지를 대화 기록에 추가함
     * - OpenAI로 전송하여 초깃값 응답을 받음
     */
    private fun handleFirstSituation(situation: String) {
        // 첫 입력이므로 이후 입력은 '대화 이어가기'로 처리
        isFirstInput = false
        // 기존 기록 초기화 (새 대화 시작)
        conversationHistory.clear()

        // 시스템 메시지: 모델에게 역할(영어교사 등)을 알려줌
        // (여기서는 간단히 '선택된 로케일 언어로 대답' 이라고 설정)
        addMessage("system", "You are an AI tutor. Please respond in ${selectedLocale.displayLanguage}.")

        // 사용자 메시지: 상황 설명
        addMessage("user", "Situation: $situation")

        // 사용자에게 대화를 시작하라고 명령 (한 문장으로 시작 등)
        addMessage("user", "Please start the conversation with one sentence.")

        // OpenAI로 전송하고 콜백에서 응답 처리
        sendToOpenAI(conversationHistory) { reply ->
            addMessage("assistant", reply) // 어시스턴트 응답 저장
            speakAndDisplay(reply)         // 화면에 표시하고 TTS로 읽기
        }
    }

    /**
     * 이미 진행 중인 대화에 사용자의 발화를 추가하고 OpenAI에게 요청해 응답을 받음
     */
    private fun continueConversation(userInput: String) {
        addMessage("user", userInput)
        sendToOpenAI(conversationHistory) { reply ->
            addMessage("assistant", reply)
            speakAndDisplay(reply)
        }
    }

    /**
     * 대화 메시지를 내부 리스트에 추가하고 RecyclerView에 반영하는 유틸
     * - role: "system"/"user"/"assistant"
     * - content: 실제 텍스트
     */
    private fun addMessage(role: String, content: String) {
        // conversationHistory는 OpenAI에 보낼 포맷으로 유지
        conversationHistory.add(mapOf("role" to role, "content" to content))

        // chatMessages는 화면에 보여주기 위한 데이터 (RecyclerView)
        chatMessages.add(ChatMessage(role, content))
        runOnUiThread {
            // 새 아이템 추가 알림 (성능상 전체 갱신보다 insert 권장)
            chatAdapter.notifyItemInserted(chatMessages.size - 1)
        }
    }

    /**
     * TTS로 읽고 화면(TextView)에 출력하는 함수
     * - TextToSpeech는 비동기적으로 재생됨
     */
    private fun speakAndDisplay(text: String) {
        textView.text = "AI: $text"
        // QUEUE_FLUSH: 이전에 대기중이던 문장은 지우고 바로 읽음
        textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * OpenAI Chat Completions API 호출 함수
     * - okhttp3를 사용 (간단한 예제)
     * - requests는 반드시 배경 스레드에서 실행
     * - 콜백은 메인 스레드에서 UI를 갱신하도록 runOnUiThread로 래핑함
     *
     * 주의:
     * - BuildConfig.OPENAI_API_KEY에 키가 설정되어 있어야 함
     * - 실제 서비스에서는 API 키를 앱 내에 하드코딩하지 말고 서버 중계 등 안전한 방식을 사용하세요
     */
    private fun sendToOpenAI(messages: List<Map<String, String>>, callback: (String) -> Unit) {
        val apiKey = BuildConfig.OPENAI_API_KEY // gradle에서 주입한 값 사용 가정
        val client = OkHttpClient()
        val url = "https://api.openai.com/v1/chat/completions"

        // messages -> JSONArray 변환
        val jsonMessages = JSONArray()
        for (msg in messages) {
            val jsonMsg = JSONObject()
            jsonMsg.put("role", msg["role"])
            jsonMsg.put("content", msg["content"])
            jsonMessages.put(jsonMsg)
        }

        // 전체 요청 JSON 구성
        val json = JSONObject().apply {
            put("model", "gpt-3.5-turbo") // 필요시 최신 모델로 변경
            put("messages", jsonMessages)
            // put("temperature", 0.8) // (선택) 톤/창의성 조절
        }

        // RequestBody 생성
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())

        // HTTP 요청 빌드
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        // 네트워크는 별도 스레드에서 실행해야 함 (안드로이드 정책)
        Thread {
            try {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw IOException("Unexpected response $response")

                val bodyStr = response.body?.string() ?: ""
                // API 응답에서 assistant의 content 추출
                val choices = JSONObject(bodyStr).getJSONArray("choices")
                val reply = choices.getJSONObject(0).getJSONObject("message").getString("content")

                // UI 갱신은 메인 스레드에서 실행
                runOnUiThread { callback(reply.trim()) }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    // 에러 발생 시 사용자에게 알림
                    textView.text = "Error: ${e.message}"
                    Toast.makeText(this, "API 호출 중 오류 발생: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * 권한 요청 결과 처리
     * - 사용자가 권한을 허용하면 음성인식을 시작함
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 권한 허용됨 -> 음성 인식 시작
                startSpeechRecognition()
            } else {
                // 권한 거부됨 -> 사용자에게 알림
                Toast.makeText(this, "마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 액티비티가 파괴되기 전에 TTS 자원 해제
     * - 메모리 누수 방지를 위해 반드시 호출
     */
    override fun onDestroy() {
        if (::textToSpeech.isInitialized) {
            // TTS 사용 중지 및 자원 해제
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        super.onDestroy()
    }
}
