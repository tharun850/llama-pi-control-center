export interface LlamaPathsConfig {
  exe_path: string;
  model_path: string;
  model_url?: string;
  hf_repo?: string;
  hf_file?: string;
  hf_token?: string;
  docker_repo?: string;
  mmproj_path?: string;
}

export interface LlamaServerConfig {
  host: string;
  port: number;
  alias: string;
  tags?: string;
  gpu_layers: number | string;
  context_size: number;
  max_tokens: number;
  threads: number;
  threads_batch: number;
  batch_size: number;
  ubatch_size: number;
  parallel_slots: number;
  flash_attention: string; // 'on' | 'off' | 'auto'
  continuous_batching: boolean;
  reasoning_mode: string; // 'on' | 'off' | 'auto'
  reasoning_format: string; // 'deepseek' | 'none' | 'deepseek-legacy'
  reasoning_budget: number;
  reasoning_preserve: boolean;
  jinja: boolean;
  chat_template: string;
  chat_template_file: string;
  chat_template_kwargs: string;
  skip_chat_parsing: boolean;
  prefill_assistant: boolean;
  slot_prompt_similarity: number;
  cache_type_k: string;
  cache_type_v: string;
  kv_offload: boolean;
  kv_unified: boolean;
  cache_ram: number;
  cache_prompt: boolean;
  cache_reuse: number;
  context_shift: boolean;
  load_mode: string; // 'auto' | 'mmap' | 'mlock' | 'mmap+mlock' | 'dio'
  split_mode: string; // 'none' | 'layer' | 'row' | 'tensor'
  main_gpu: number;
  fit: boolean;
  timeout: number;
  sse_ping_interval: number;
  threads_http: number;
  cors_origins: string;
  webui: boolean;
  metrics: boolean;
  slots_endpoint: boolean;
  advanced: string;
}

export interface LlamaSamplingConfig {
  temperature: number;
  top_k: number;
  top_p: number;
  min_p: number;
  repeat_penalty: number;
  repeat_last_n: number;
  presence_penalty: number;
  frequency_penalty: number;
  dry_multiplier: number;
  dry_base: number;
  samplers: string;
}

export interface FullLlamaConfig {
  paths: LlamaPathsConfig;
  server: LlamaServerConfig;
  sampling: LlamaSamplingConfig;
}

export interface ServerStatus {
  running: boolean;
  healthy: boolean;
  pid: number | null;
  status: 'STOPPED' | 'STARTING' | 'RUNNING' | 'ERROR';
  startTime: number | null;
  uptimeSeconds: number;
  command?: string;
}

export interface LogEntry {
  id: number;
  timestamp: number;
  level: 'INFO' | 'WARN' | 'ERROR' | 'SLOT' | 'SYSTEM';
  message: string;
}

export interface ScannedFile {
  name: string;
  path: string;
  sizeBytes: number;
  sizeFormatted: string;
  lastModified: number;
}

export interface ConfigPreset {
  id: string;
  name: string;
  description: string;
  config: FullLlamaConfig;
}
