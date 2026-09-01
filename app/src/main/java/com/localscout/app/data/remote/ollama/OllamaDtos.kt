package com.localscout.app.data.remote.ollama

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format for Ollama's /api/chat endpoint.
 *
 * Uses `format: "json"` to constrain gemma4:e4b (and other structured-output
 * models) to JSON-only output. The model's `message.content` will then be a
 * valid JSON-as-string. Ollama may also include a `message.thinking` field
 * with the model's reasoning — that's safe because ChatResponse declares
 * only the fields we care about and kotlinx-serialization is configured to
 * ignore unknowns.
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    // @EncodeDefault guards against a Json{} config without encodeDefaults=true:
    // ollama treats a missing `stream` as stream=true (NDJSON response).
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val stream: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val format: String = "json",
    val options: ChatOptions = ChatOptions(),
)

@Serializable
data class ChatMessage(
    val role: String,           // "system" | "user" | "assistant"
    val content: String,
)

@Serializable
data class ChatOptions(
    val temperature: Double = 0.2,
    @SerialName("num_ctx") val numCtx: Int = 8192,
)

@Serializable
data class ChatResponse(
    val model: String,
    @SerialName("created_at") val createdAt: String,
    val message: ChatMessage,
    @SerialName("done_reason") val doneReason: String? = null,
    @SerialName("total_duration") val totalDuration: Long? = null,
    @SerialName("eval_count") val evalCount: Int? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int? = null,
)

/** GET /api/tags response, used to verify a host is reachable. */
@Serializable
data class TagsResponse(
    @SerialName("models") val models: List<OllamaModel> = emptyList(),
)

@Serializable
data class OllamaModel(
    val name: String,
    @SerialName("modified_at") val modifiedAt: String? = null,
    val size: Long? = null,
)
