# Performance diagnostics

Settings → Export diagnostics saves a versioned JSON report directly to Downloads. No storage
permission is required. This capture is local: it has no analytics or upload transport and does not
use the legacy Firebase metrics logger. It starts with the app; no developer tools are needed.

After installing this change, load the model, perform several representative turns, and run the
benchmark screen with the same model and backend. Include a short/new conversation, a longer
conversation, and repeated runs to expose warm-up and sustained thermal behavior. Finish or stop the
run, then export. Prior chat performance cannot be recovered from an older installation. Previously
saved benchmark results are included even if they predate this recorder.

## Recorded data

| Area | Measurements and context |
| --- | --- |
| Initialization | Success/failure, overall model initialization time, recorder elapsed time, engine/conversation checkpoints, model filename/size/modification time, engine ID |
| Applied runtime | LiteRT-LM and app versions, source commit, configured text/vision/audio backends, CPU thread setting or explicit runtime default, context limit, activation dtype, speculative decoding capability and effective setting, cache policy |
| Conversation | Session/turn IDs, sampler settings captured when the conversation is created/reset, tool/initial-history counts at initialization, per-request thinking/output limit, text length and image/audio dimensions/counts/bytes |
| Streaming | App time to first nonempty content callback, total time, decode interval after first content, callback gaps/counts and answer/thinking character counts; no text bodies |
| Native inference | Prefill token count/rate captured at first content before channel stripping overwrites it; finalized decode token count/rate and native TTFT, when the runtime supplies them |
| Context | Native KV-cache token counts before/after successful turns; cancellation after output can supply partial native results; initial history can be deferred until first prefill |
| Benchmarks | Requested token counts/backend, per-run actual native counts, init/TTFT/prefill/decode measurements, cache conditions, raw saved series with units, errors/cancellations |
| Resource timeline | Process PSS/RSS/peak RSS, attributed graphics memory, Java/native heaps, process CPU time and CPU percentage relative to one core, thread count, readable CPU frequencies, available RAM, ART GC/allocation counters |
| Thermal and battery | Android thermal severity/headroom, battery temperature/current/voltage/level/charge/plug state, power-save and idle state |
| UI responsiveness | Activity frame count, mean/max frame duration, frames over their individual platform deadlines, dropped frame-metric reports |
| Reliability | Error/cancel/reset/unload outcomes, unfinished-run checkpoints, Android historical process-exit reasons and last PSS/RSS, bounded current-process main/crash logs |
| Export context | SoC/device/OS/ABI identity, offscreen GL vendor/renderer/version, storage headroom, model download states and current settings |

Synchronous helper requests have native metrics but no app streaming TTFT; the completed reply is not
passed through a fake streaming callback. Ephemeral requests have independent trackers. Native
queries are skipped on errors and cancellation before any output, avoiding executor lock contention
and stale values. Reset/unload finishes an active record without querying a closed conversation.

## Interpretation and limits

- Backend names describe the `EngineConfig` passed to LiteRT-LM. Accelerator registration messages
  and GL renderer identity do not prove every inference operation ran there. Per-op placement,
  fallback counts, kernel timings, GPU utilization/VRAM/bandwidth, NPU counters, and KV-cache allocation
  size/precision require native runtime instrumentation or a device profiler; this API does not
  expose them. Missing readings are null/absent with availability notes, never invented zeros.
- Callback chunks are not model tokens. Native speed and app TTFT are separate measurements with
  different boundaries. Do not divide a character/chunk count by elapsed time and call it tokens/sec.
- Resources describe the whole app process/device and are shared between overlapping operations.
  Graphics attribution is not physical VRAM. Battery readings are not model power/energy measurements.
- Resource sampling runs on one background IO worker, nominally once a second during active work,
  with an asynchronous tail probe. Sample offsets and delay after completion make that boundary
  visible. Very short spikes can be missed. Probe duration is reported. Native benchmark mode and
  recording have nonzero overhead. Thermal headroom is cached across probes for at least 10 seconds;
  1.0 is the severe-throttling threshold, not remaining thermal capacity.
- Fresh benchmark app cache is distinct from OS page cache: no cache flushing or performance tuning
  is performed. A failed temporary-directory creation is explicitly labelled shared app cache.
- Structured turn coverage is LiteRT-LM through `LlmChatModelHelper`/`LlmModelInstance`, plus the
  benchmark runner. Other runtimes, ASR/TTS and external conversation owners have no equivalent
  native turn capture; device/exit/log context can still help diagnose their failures.

## Retention and privacy

An atomic app-private file in Android's no-backup directory holds the latest 120 operations within 2 MiB, with eviction counts and
storage errors included in the export. Start records and 10-second checkpoints survive restarts;
completed records replace their running checkpoint. A RUNNING record from a previous process session
means the process exited without completion, not that inference is still running. Compare it with
Android exit history. A forced exit can lose the latest 10 seconds, or the initial queued write.
Each operation keeps at most 64 raw resource samples (first and latest samples) while its aggregate
peaks/means/counts include all samples. Clearing app data removes the history.

The structured recorder retains counts/configuration instead of prompts, replies, tool definitions,
URLs, or media. Model objects are never serialized. Error messages and raw logcat can contain user
content; review the exported file before sharing it. Known current access/refresh tokens, Hugging
Face tokens, and authorization/credential patterns are redacted. Redaction acts on JSON string
values before serialization, so it preserves a parseable report. No secret or signature changes are
part of this feature.

## Validation without packaging an APK

The Android Actions workflow runs `:app:testDebugUnitTest` on a feature-branch dispatch or a PR.
APK assembly, signing-key restoration, APK artifact upload and release publication require `main`.
Do not merge/push this feature to `main` until APK packaging is requested. Unit tests cover history
retention/reload/corruption/storage failure, credential redaction and valid JSON, unavailable and
non-finite readings, raw native metrics and benchmark units, streaming timing/concurrent callbacks,
frame deadlines, bounded samples with full-run aggregates, and actual inference lifecycle behavior
(success, error, cancellation before/after output, session reset, duplicate completion, and
synchronous requests). Device-specific counters and native
behavior require a later on-device check after an APK is authorized.
