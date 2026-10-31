#pragma once
// Trimmed subset of upstream cpp/common/chat-auto-parser.h (cui-llama.rn v1.12.2).
// Only autoparser::generation_params — the one struct chat.h passes by const&.
// Field-for-field verbatim from upstream lines 54-84.
#include "chat.h"
#include "common.h"
#include "nlohmann/json.hpp"

#include <chrono>
#include <string>

namespace autoparser {

struct generation_params {
    json                                  messages;
    json                                  tools;
    common_chat_tool_choice               tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    json                                  json_schema;
    bool                                  parallel_tool_calls = true;
    common_reasoning_format               reasoning_format    = COMMON_REASONING_FORMAT_AUTO;
    bool                                  stream              = true;
    std::string                           grammar;
    bool                                  add_generation_prompt  = false;
    common_chat_continuation              continue_final_message = COMMON_CHAT_CONTINUATION_NONE;
    common_chat_msg                       continue_msg;
    bool                                  enable_thinking        = true;
    std::chrono::system_clock::time_point now                    = std::chrono::system_clock::now();
    json                                  extra_context;
    bool                                  add_bos       = false;
    bool                                  add_eos       = false;
    bool                                  is_inference  = true;
    bool                                  add_inference = false;
    bool                                  mark_input    = true;  // whether to mark input strings in the jinja context

    bool has_continuation() const {
        return continue_final_message != COMMON_CHAT_CONTINUATION_NONE && !continue_msg.empty();
    }
};

}  // namespace autoparser
