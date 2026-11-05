package com.jarvis.ai.conversation.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Only finalized user transcript and provider event identity are accepted. */
public class PrepareVoiceTurnRequest {
    @NotBlank
    @Size(max = 20_000)
    private String content;

    @NotBlank
    @Size(max = 200)
    private String externalEventId;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String externalEventId) { this.externalEventId = externalEventId; }
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown request property: " + name);
    }
}
