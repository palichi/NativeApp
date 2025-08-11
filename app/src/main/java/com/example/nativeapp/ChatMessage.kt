package com.example.nativeapp

data class ChatMessage(
    val role: String,  // "user" or "assistant"
    val content: String
)