package com.hitorro.haassistant.conversation;

/**
 * The output of a tool call.
 *
 * If {@code awaitUser} is true, the conversation loop stops after this tool
 * runs, sends {@code userPrompt} back to the UI, and waits for the user's
 * next message to resume. This is how the {@code ask_user} tool implements
 * clarifying dialogs.
 */
public record ToolResult(Object output, boolean awaitUser, String userPrompt) {

    public static ToolResult ok(Object output) {
        return new ToolResult(output, false, null);
    }

    public static ToolResult askUser(String prompt) {
        return new ToolResult(prompt, true, prompt);
    }

    public static ToolResult error(String message) {
        return new ToolResult("ERROR: " + message, false, null);
    }
}
