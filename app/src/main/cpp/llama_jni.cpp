#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ChipITLlama", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ChipITLlama", __VA_ARGS__)

namespace {

struct LlamaSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel_flag{false};
    std::mutex mutex;
};

/**
 * Decodes complete UTF-8 sequences from `buf` into UTF-16 `out` (safe for JNI NewString,
 * including 4-byte emoji surrogate pairs), erasing consumed bytes and keeping incomplete
 * trailing multi-byte UTF-8 sequences buffered for the next token.
 */
void drain_utf8_to_utf16(std::string & buf, std::vector<jchar> & out) {
    size_t i = 0;
    const size_t n = buf.size();
    while (i < n) {
        const unsigned char b0 = static_cast<unsigned char>(buf[i]);
        uint32_t cp = 0;
        size_t len = 1;
        if (b0 < 0x80) {
            cp = b0;
            len = 1;
        } else if ((b0 & 0xE0) == 0xC0) {
            len = 2;
            if (i + len > n) break;
            const unsigned char b1 = static_cast<unsigned char>(buf[i + 1]);
            if ((b1 & 0xC0) != 0x80) {
                out.push_back(0xFFFD);
                i += 1;
                continue;
            }
            cp = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        } else if ((b0 & 0xF0) == 0xE0) {
            len = 3;
            if (i + len > n) break;
            const unsigned char b1 = static_cast<unsigned char>(buf[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(buf[i + 2]);
            if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) {
                out.push_back(0xFFFD);
                i += 1;
                continue;
            }
            cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
        } else if ((b0 & 0xF8) == 0xF0) {
            len = 4;
            if (i + len > n) break;
            const unsigned char b1 = static_cast<unsigned char>(buf[i + 1]);
            const unsigned char b2 = static_cast<unsigned char>(buf[i + 2]);
            const unsigned char b3 = static_cast<unsigned char>(buf[i + 3]);
            if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                out.push_back(0xFFFD);
                i += 1;
                continue;
            }
            cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        } else {
            out.push_back(0xFFFD);
            i += 1;
            continue;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
        i += len;
    }
    if (i > 0) {
        buf.erase(0, i);
    }
}

std::string format_prompt_with_template(const llama_model * model, const std::string & raw_prompt) {
    const std::string sys_tag = "<|system|>\n";
    const std::string usr_tag = "\n<|user|>\n";
    const std::string ast_tag = "\n<|assistant|>\n";

    auto pos_sys = raw_prompt.find(sys_tag);
    auto pos_usr = raw_prompt.find(usr_tag);
    auto pos_ast = raw_prompt.find(ast_tag);

    if (pos_sys == 0 && pos_usr != std::string::npos && pos_ast != std::string::npos && pos_ast > pos_usr) {
        std::string sys_msg = raw_prompt.substr(sys_tag.size(), pos_usr - sys_tag.size());
        std::string usr_msg = raw_prompt.substr(pos_usr + usr_tag.size(), pos_ast - (pos_usr + usr_tag.size()));

        const char * tmpl = llama_model_chat_template(model, nullptr);
        if (tmpl != nullptr) {
            llama_chat_message chat[2] = {
                {"system", sys_msg.c_str()},
                {"user", usr_msg.c_str()}
            };
            int32_t needed = llama_chat_apply_template(tmpl, chat, 2, true, nullptr, 0);
            if (needed > 0) {
                std::string formatted(static_cast<size_t>(needed) + 1, '\0');
                int32_t written = llama_chat_apply_template(tmpl, chat, 2, true, formatted.data(), static_cast<int32_t>(formatted.size()));
                if (written > 0) {
                    formatted.resize(static_cast<size_t>(written));
                    return formatted;
                }
            }
        }
    }
    return raw_prompt;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeLoadModel(
        JNIEnv *env, jobject /* thiz */,
        jstring model_path, jint threads, jint gpu_layers,
        jint context_len, jboolean use_vulkan, jboolean use_opencl) {
    if (model_path == nullptr) return 0L;
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0L;

    static std::once_flag backend_once;
    std::call_once(backend_once, []() {
        llama_backend_init();
    });

    llama_model_params mparams = llama_model_default_params();
#ifdef GGML_USE_VULKAN
    mparams.n_gpu_layers = (use_vulkan || use_opencl) ? gpu_layers : 0;
#else
    (void) use_vulkan;
    (void) use_opencl;
    (void) gpu_layers;
    mparams.n_gpu_layers = 0;
#endif

    LOGI("Loading model=%s ctx=%d threads=%d gpuLayers=%d",
         path, context_len, threads, mparams.n_gpu_layers);

    llama_model * model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (model == nullptr) {
        LOGE("llama_model_load_from_file failed");
        return 0L;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(context_len > 0 ? context_len : 2048);
    cparams.n_batch = 512;
    cparams.n_threads = threads > 0 ? threads : 4;
    cparams.n_threads_batch = cparams.n_threads;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model failed");
        llama_model_free(model);
        return 0L;
    }

    auto * session = new LlamaSession();
    session->model = model;
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeGenerate(
        JNIEnv *env, jobject /* thiz */,
        jlong handle, jstring prompt,
        jint max_tokens, jfloat temperature, jfloat top_p,
        jint top_k, jfloat repeat_penalty, jobject callback) {
    if (handle == 0L || prompt == nullptr || callback == nullptr) return;

    auto * session = reinterpret_cast<LlamaSession *>(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->model == nullptr || session->ctx == nullptr) return;

    session->cancel_flag.store(false);

    const char * raw_cstr = env->GetStringUTFChars(prompt, nullptr);
    if (raw_cstr == nullptr) return;
    std::string raw_prompt(raw_cstr);
    env->ReleaseStringUTFChars(prompt, raw_cstr);

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)V");
    jmethodID on_metrics_mid = env->GetMethodID(cb_cls, "onMetrics", "(JDDII)V");
    env->DeleteLocalRef(cb_cls);
    if (on_token_mid == nullptr || on_metrics_mid == nullptr) return;

    // Reset KV cache for clean turn execution
    llama_memory_t mem = llama_get_memory(session->ctx);
    if (mem != nullptr) {
        llama_memory_clear(mem, true);
    }

    const std::string formatted = format_prompt_with_template(session->model, raw_prompt);
    const llama_vocab * vocab = llama_model_get_vocab(session->model);

    const int32_t n_prompt_est = -llama_tokenize(
        vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()),
        nullptr, 0, true, true
    );
    if (n_prompt_est <= 0) {
        LOGE("Tokenization failed");
        return;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(n_prompt_est));
    const int32_t n_prompt = llama_tokenize(
        vocab, formatted.c_str(), static_cast<int32_t>(formatted.size()),
        prompt_tokens.data(), n_prompt_est, true, true
    );
    if (n_prompt <= 0) return;
    prompt_tokens.resize(static_cast<size_t>(n_prompt));

    const uint32_t n_ctx = llama_n_ctx(session->ctx);
    if (static_cast<uint32_t>(n_prompt) >= n_ctx) {
        LOGE("Prompt (%d tokens) exceeds context length (%u)", n_prompt, n_ctx);
        return;
    }

    const auto t_start = std::chrono::steady_clock::now();
    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(session->ctx));
    for (int32_t offset = 0; offset < n_prompt; offset += n_batch) {
        if (session->cancel_flag.load()) return;
        const int32_t chunk = std::min(n_batch, n_prompt - offset);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, chunk);
        if (llama_decode(session->ctx, batch) != 0) {
            LOGE("llama_decode prefill failed at offset=%d", offset);
            return;
        }
    }

    const auto t_prefill_end = std::chrono::steady_clock::now();
    const long long ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t_prefill_end - t_start).count();
    const double prefill_sec = std::max(0.001, ttft_ms / 1000.0);
    const double prefill_tps = static_cast<double>(n_prompt) / prefill_sec;

    auto sparams = llama_sampler_chain_default_params();
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    if (top_p > 0.0f && top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    if (repeat_penalty != 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    const int32_t limit = std::min(max_tokens > 0 ? max_tokens : 512, static_cast<int32_t>(n_ctx) - n_prompt - 1);
    const auto t_decode_start = std::chrono::steady_clock::now();
    int32_t gen_count = 0;
    std::string pending_utf8;
    std::vector<jchar> utf16_buf;

    for (int32_t step = 0; step < limit; ++step) {
        if (session->cancel_flag.load()) break;

        llama_token tok = llama_sampler_sample(smpl, session->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[256];
        int32_t n_chars = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n_chars < 0) {
            std::vector<char> big(static_cast<size_t>(-n_chars));
            n_chars = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
            if (n_chars > 0) pending_utf8.append(big.data(), static_cast<size_t>(n_chars));
        } else if (n_chars > 0) {
            pending_utf8.append(piece, static_cast<size_t>(n_chars));
        }

        gen_count++;
        const auto t_now = std::chrono::steady_clock::now();
        const double dec_sec = std::max(0.001, std::chrono::duration<double>(t_now - t_decode_start).count());
        const double decode_tps = static_cast<double>(gen_count) / dec_sec;

        env->CallVoidMethod(
            callback,
            on_metrics_mid,
            static_cast<jlong>(ttft_ms),
            static_cast<jdouble>(prefill_tps),
            static_cast<jdouble>(decode_tps),
            static_cast<jint>(n_prompt),
            static_cast<jint>(gen_count)
        );

        utf16_buf.clear();
        drain_utf8_to_utf16(pending_utf8, utf16_buf);
        if (!utf16_buf.empty()) {
            jstring jtok = env->NewString(utf16_buf.data(), static_cast<jsize>(utf16_buf.size()));
            if (jtok != nullptr) {
                env->CallVoidMethod(callback, on_token_mid, jtok);
                env->DeleteLocalRef(jtok);
            }
        }

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }

        llama_batch next_batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(session->ctx, next_batch) != 0) {
            LOGE("llama_decode step failed at step=%d", step);
            break;
        }
    }

    llama_sampler_free(smpl);
}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeCancel(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    if (handle == 0L) return;
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    session->cancel_flag.store(true);
}

JNIEXPORT void JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeUnload(JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    if (handle == 0L) return;
    auto * session = reinterpret_cast<LlamaSession *>(handle);
    session->cancel_flag.store(true);
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->ctx != nullptr) {
            llama_free(session->ctx);
            session->ctx = nullptr;
        }
        if (session->model != nullptr) {
            llama_model_free(session->model);
            session->model = nullptr;
        }
    }
    delete session;
}

JNIEXPORT jboolean JNICALL
Java_com_chipit_app_engine_LlamaJni_nativeSupportsVulkan(JNIEnv * /* env */, jobject /* thiz */) {
#ifdef GGML_USE_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

}
