#pragma once
// Trimmed subset of upstream cpp/common/common.h (llama.cpp b10375, cui-llama.rn v1.12.2).
// Only the declarations chat.h needs; field-for-field verbatim from upstream.
#include <string>
#include <vector>

#include "llama.h"

using llama_tokens = std::vector<llama_token>;

enum common_reasoning_format {
    COMMON_REASONING_FORMAT_NONE,
    COMMON_REASONING_FORMAT_AUTO,            // Same as deepseek, using `message.reasoning_content`
    COMMON_REASONING_FORMAT_DEEPSEEK_LEGACY, // Extract thinking tag contents and return as `message.reasoning_content`, or leave inline in <think> tags in stream mode
    COMMON_REASONING_FORMAT_DEEPSEEK,        // Extract thinking tag contents and return as `message.reasoning_content`, including in streaming deltas.
    // do not extend this enum unless you absolutely have to
    // in most cases, use COMMON_REASONING_FORMAT_AUTO
    // see: https://github.com/ggml-org/llama.cpp/pull/15408
};

enum common_grammar_trigger_type {
    COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN,
    COMMON_GRAMMAR_TRIGGER_TYPE_WORD,
    COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN,
    COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL,
};

struct common_grammar_trigger {
    common_grammar_trigger_type type;
    std::string value;
    llama_token token = LLAMA_TOKEN_NULL;
};
