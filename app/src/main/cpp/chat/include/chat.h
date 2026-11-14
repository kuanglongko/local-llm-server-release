// Chat support (incl. tool call grammar constraining & output parsing) w/ generic & custom template handlers.
//
// Trimmed subset of upstream vendor/llama.cpp/common/chat.h (cui-llama.rn v1.13.0).
// Only the declarations llama_jni.cpp uses; every struct field is verbatim from
// upstream in the same order, so the layouts match the prebuilt librnllama*.so.
// The jinja-template / autoparser machinery is intentionally absent: it is never
// instantiated here, only crossed through common_chat_templates_* handles.

#pragma once

#include "common.h"
#include "peg-parser.h"

#include "json.h"

#include <chrono>
#include <initializer_list>
#include <map>
#include <memory>
#include <string>
#include <vector>

struct common_chat_templates;

struct common_chat_tool_call {
    std::string name;
    std::string arguments;
    std::string id;

    bool operator==(const common_chat_tool_call & other) const {
        return name == other.name && arguments == other.arguments && id == other.id;
    }
};

struct common_chat_msg_content_part {
    std::string type;
    std::string text;
    std::string name; // tool_reference: name of a tool from the request tools
    // Preserves non-standard fields from the original JSON (e.g. source_lang_code,
    // target_lang_code for TranslateGemma) so they survive the parse/serialize round-trip.
    common_json extra_fields;

    // TODO @ngxson : no known chat templates support reasoning_content in content parts yet
    //                this can be useful for models with interleaved thinking (like Kimi-K2)
    //                if you see any templates explicitly support this, please ping me
    // std::string reasoning_content;

    bool operator==(const common_chat_msg_content_part & other) const {
        return type == other.type && text == other.text && name == other.name && extra_fields == other.extra_fields;
    }
};

struct common_chat_msg {
    std::string                               role;
    std::string                               content;
    std::vector<common_chat_msg_content_part> content_parts;
    std::vector<common_chat_tool_call>        tool_calls;
    std::string                               reasoning_content;
    std::string                               tool_name;
    std::string                               tool_call_id;
    // non-OpenAI message keys as a JSON object string, only for templates that read them (DeepSeek V4.1)
    std::string                               extra_json;

    common_json to_json_oaicompat(bool concat_typed_text = false) const;

    std::string render_content(const std::string & delimiter = "\n\n") const;

    bool empty() const {
        return content.empty() && content_parts.empty() && tool_calls.empty() && reasoning_content.empty() &&
               tool_name.empty() && tool_call_id.empty();
    }

    bool contains_media() const {
        for (const auto & part : content_parts) {
            if (part.type == "media_marker") {
                return true;
            }
        }
        return false;
    }

    bool operator==(const common_chat_msg & other) const {
        return role == other.role && content == other.content && content_parts == other.content_parts &&
               tool_calls == other.tool_calls && reasoning_content == other.reasoning_content &&
               tool_name == other.tool_name && tool_call_id == other.tool_call_id;
    }

    bool operator!=(const common_chat_msg & other) const { return !(*this == other); }
};

enum common_chat_role {
    COMMON_CHAT_ROLE_UNKNOWN,
    COMMON_CHAT_ROLE_SYSTEM,
    COMMON_CHAT_ROLE_ASSISTANT,
    COMMON_CHAT_ROLE_USER,
    COMMON_CHAT_ROLE_TOOL
};

struct common_chat_msg_delimiter {
    common_chat_role role = COMMON_CHAT_ROLE_UNKNOWN;
    std::string      delimiter;
    llama_tokens     tokens = {};
};

struct common_chat_msg_delimiters {
    std::vector<common_chat_msg_delimiter> delimiters;

    common_chat_msg_delimiters() = default;
    common_chat_msg_delimiters(std::initializer_list<common_chat_msg_delimiter> delims) : delimiters(delims) {}
};

struct common_chat_tool {
    std::string name;
    std::string description;
    std::string parameters;

    // withheld from the system prompt, a tool_reference reveals it later in the conversation
    bool defer_loading = false;
};

enum common_chat_tool_choice {
    COMMON_CHAT_TOOL_CHOICE_AUTO,
    COMMON_CHAT_TOOL_CHOICE_REQUIRED,
    COMMON_CHAT_TOOL_CHOICE_NONE,
};

enum common_chat_format {
    COMMON_CHAT_FORMAT_CONTENT_ONLY,

    // These are intended to be parsed by the PEG parser
    COMMON_CHAT_FORMAT_PEG_SIMPLE,
    COMMON_CHAT_FORMAT_PEG_NATIVE,
    COMMON_CHAT_FORMAT_PEG_GEMMA4,
    COMMON_CHAT_FORMAT_PEG_MINIMAX_M3,

    COMMON_CHAT_FORMAT_COUNT,  // Not a format, just the # formats
};


// Continuation method provided via `continue_final_message`
enum common_chat_continuation {
    COMMON_CHAT_CONTINUATION_NONE,
    COMMON_CHAT_CONTINUATION_AUTO,
    COMMON_CHAT_CONTINUATION_REASONING,
    COMMON_CHAT_CONTINUATION_CONTENT,
};

struct common_chat_templates_inputs {
    std::vector<common_chat_msg>          messages;
    std::string                           grammar;
    std::string                           json_schema;
    bool                                  add_generation_prompt  = true;
    common_chat_continuation              continue_final_message = COMMON_CHAT_CONTINUATION_NONE;
    bool                                  use_jinja              = true;
    // Parameters below only supported when use_jinja is true
    std::vector<common_chat_tool>         tools;
    common_chat_tool_choice               tool_choice         = COMMON_CHAT_TOOL_CHOICE_AUTO;
    bool                                  parallel_tool_calls = false;
    common_reasoning_format               reasoning_format    = COMMON_REASONING_FORMAT_NONE; // TODO: refactor this to "bool enable_thinking"
    bool                                  enable_thinking     = true;
    std::chrono::system_clock::time_point now                 = std::chrono::system_clock::now();
    std::map<std::string, std::string>    chat_template_kwargs;
    bool                                  add_bos = false;
    bool                                  add_eos = false;
    bool                                  force_pure_content = false;
};

struct common_chat_prompt_part {
    std::string text;
    bool        is_input = false; // from user input, special tokens inside must not be parsed
};

// rendered chat template, split into template parts and user input parts
struct common_chat_prompt {
    std::vector<common_chat_prompt_part> parts;

    common_chat_prompt() = default;

    // single part of template text
    explicit common_chat_prompt(std::string text) { parts.push_back({std::move(text), false}); }

    std::string str() const;

    void append(const std::string & text, bool is_input = false);

    // replace the last occurrence of `from`, user input parts are skipped
    void replace_last(const std::string & from, const std::string & to);
};

struct common_chat_params {
    common_chat_format                  format = COMMON_CHAT_FORMAT_CONTENT_ONLY;
    common_chat_prompt                  prompt;
    std::string                         grammar;
    bool                                grammar_lazy         = false;
    std::string                         generation_prompt;
    bool                                supports_thinking    = false;
    std::string                         thinking_start_tag;  // e.g., "<think>"
    std::vector<std::string>            thinking_end_tags;   // e.g., "</think>"
    std::vector<common_grammar_trigger> grammar_triggers;
    std::vector<std::string>            preserved_tokens;
    std::vector<std::string>            additional_stops;
    common_peg_arena                    parser;
    common_chat_msg_delimiters          message_delimiters;
};

struct common_chat_input {
    std::string              text;
    std::vector<llama_token> tokens;

    common_chat_input() = default;

    // plain text, with no tokens
    explicit common_chat_input(std::string text) : text(std::move(text)), tokens(this->text.size(), LLAMA_TOKEN_NULL) {}

    size_t size() const { return text.size(); }
    bool empty() const { return text.empty(); }

    void append(const std::string & piece, llama_token token);
    void append(const common_chat_input & chunk);

    void prepend(const std::string & prefix);
    void prepend(const common_chat_input & prefix);

    void truncate(size_t pos);

    common_chat_input substr(size_t pos, size_t n = std::string::npos) const;
};

common_chat_input common_chat_input_tokenize(const llama_vocab * vocab, const std::string & text);

// per-message parsing syntax
// should be derived from common_chat_params
struct common_chat_parser_params {
    common_chat_format format = COMMON_CHAT_FORMAT_CONTENT_ONLY;
    common_chat_input  generation_prompt;
    bool               debug  = false; // Enable debug output for PEG parser
    common_peg_arena   parser = {};
    common_chat_parser_params() = default;
    common_chat_parser_params(const common_chat_params & chat_params) {
        format  = chat_params.format;
        generation_prompt = common_chat_input(chat_params.generation_prompt);
    }
};

void common_chat_templates_free(struct common_chat_templates * tmpls);

struct common_chat_templates_deleter {
    void operator()(common_chat_templates * tmpls) { common_chat_templates_free(tmpls); }
};

typedef std::unique_ptr<struct common_chat_templates, common_chat_templates_deleter> common_chat_templates_ptr;

common_chat_templates_ptr common_chat_templates_init(const struct llama_model * model,
                                                     const std::string &        chat_template_override,
                                                     const std::string &        bos_token_override = "",
                                                     const std::string &        eos_token_override = "");

struct common_chat_params common_chat_templates_apply(const struct common_chat_templates *        tmpls,
                                                      const struct common_chat_templates_inputs & inputs);

const char *    common_chat_format_name(common_chat_format format);
common_chat_msg common_chat_parse(const common_chat_input & input, bool is_partial, const common_chat_parser_params & params);
common_chat_msg common_chat_peg_parse(const common_peg_arena & src_parser, const common_chat_input & input, bool is_partial, const common_chat_parser_params & params);

common_chat_tool_choice common_chat_tool_choice_parse_oaicompat(const std::string & tool_choice);

// Parses a JSON array of messages in OpenAI's chat completion API format.
std::vector<common_chat_tool> common_chat_tools_parse_oaicompat(const common_json & tools);
