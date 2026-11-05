package com.jarvis.ai.conversation.dto;

public record VoiceContextBudget(int maxCharacters, int selectedCharacters,
                                 int messageCharacters, int memoryCharacters,
                                 String estimation) { }
