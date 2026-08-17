import { Injectable, signal, computed, effect, inject } from '@angular/core';
import { ApiService } from './api.service';
import { FullLlamaConfig, ServerStatus, LogEntry, ScannedFile, ConfigPreset } from '../models/llama-config.model';
import { ProjectInfo, OpenCodeConfig } from '../models/project.model';

@Injectable({
  providedIn: 'root'
})
export class StateService {
  private api = inject(ApiService);

  // --- Active Tab State ---
  activeTab = signal<'config' | 'pi' | 'opencode' | 'logs' | 'chat'>('config');

  // --- Server Status Signal ---
  serverStatus = signal<ServerStatus>({
    running: false,
    healthy: false,
    pid: null,
    status: 'STOPPED',
    startTime: null,
    uptimeSeconds: 0,
    command: ''
  });

  // --- Loading / Action Status ---
  isStarting = signal<boolean>(false);
  isStopping = signal<boolean>(false);
  notification = signal<{ type: 'success' | 'error' | 'info'; message: string } | null>(null);

  // --- Full Llama Config Signal ---
  config = signal<FullLlamaConfig>({
    paths: {
      exe_path: 'C:\\AI\\llama.cpp\\llama-server.exe',
      model_path: 'C:\\AI\\Models\\Qwen3.8-27B-Q4_K_M.gguf',
      model_url: '',
      hf_repo: '',
      hf_file: '',
      hf_token: '',
      docker_repo: '',
      mmproj_path: ''
    },
    server: {
      host: '127.0.0.1',
      port: 9090,
      alias: 'qwen3.8',
      tags: '',
      gpu_layers: '' as any,
      context_size: '' as any,
      max_tokens: '' as any,
      threads: '' as any,
      threads_batch: '' as any,
      batch_size: '' as any,
      ubatch_size: '' as any,
      parallel_slots: '' as any,
      flash_attention: 'auto',
      continuous_batching: 'auto' as any,
      reasoning_mode: 'auto',
      reasoning_format: 'auto',
      reasoning_budget: '' as any,
      reasoning_preserve: false,
      jinja: false,
      chat_template: '',
      chat_template_file: '',
      chat_template_kwargs: '',
      skip_chat_parsing: false,
      prefill_assistant: false,
      slot_prompt_similarity: '' as any,
      cache_type_k: 'auto',
      cache_type_v: 'auto',
      kv_offload: 'auto' as any,
      kv_unified: 'auto' as any,
      cache_ram: '' as any,
      cache_prompt: 'auto' as any,
      cache_reuse: '' as any,
      context_shift: false,
      load_mode: 'auto',
      split_mode: 'auto',
      main_gpu: '' as any,
      fit: 'auto' as any,
      timeout: '' as any,
      sse_ping_interval: '' as any,
      threads_http: '' as any,
      cors_origins: '*',
      webui: false,
      metrics: false,
      slots_endpoint: false,
      advanced: ''
    },
    sampling: {
      temperature: '' as any,
      top_k: '' as any,
      top_p: '' as any,
      min_p: '' as any,
      repeat_penalty: '' as any,
      repeat_last_n: '' as any,
      presence_penalty: '' as any,
      frequency_penalty: '' as any,
      dry_multiplier: '' as any,
      dry_base: '' as any,
      samplers: ''
    }
  });

  // --- Presets & Discovered Files ---
  presets = signal<ConfigPreset[]>([]);
  scannedModels = signal<ScannedFile[]>([]);
  scannedExecutables = signal<ScannedFile[]>([]);

  // --- Logs ---
  logs = signal<LogEntry[]>([]);
  private eventSource: EventSource | null = null;

  // --- OpenCode Projects ---
  projects = signal<ProjectInfo[]>([]);
  selectedProject = signal<ProjectInfo | null>(null);

  // --- Generated CLI Preview Computed ---
  generatedCommand = computed(() => {
    const c = this.config();
    const cmd: string[] = [];

    cmd.push(c.paths.exe_path || 'llama-server.exe');

    if (c.paths.model_path && c.paths.model_path.trim()) {
      cmd.push('-m', `"${c.paths.model_path.trim()}"`);
    }
    if (c.paths.model_url && c.paths.model_url.trim()) {
      cmd.push('--model-url', `"${c.paths.model_url.trim()}"`);
    }
    if (c.paths.hf_repo && c.paths.hf_repo.trim()) {
      cmd.push('--hf-repo', c.paths.hf_repo.trim());
    }
    if (c.paths.hf_file && c.paths.hf_file.trim()) {
      cmd.push('--hf-file', c.paths.hf_file.trim());
    }
    if (c.paths.hf_token && c.paths.hf_token.trim()) {
      cmd.push('--hf-token', c.paths.hf_token.trim());
    }
    if (c.paths.mmproj_path && c.paths.mmproj_path.trim()) {
      cmd.push('--mmproj', `"${c.paths.mmproj_path.trim()}"`);
    }

    if (c.server.host && c.server.host.trim()) cmd.push('--host', c.server.host.trim());
    if (c.server.port !== undefined && c.server.port !== null && String(c.server.port).trim() !== '') {
      cmd.push('--port', String(c.server.port));
    }
    if (c.server.alias && c.server.alias.trim()) cmd.push('--alias', c.server.alias.trim());

    // Hardware & Offloading
    if (c.server.gpu_layers !== undefined && c.server.gpu_layers !== null && String(c.server.gpu_layers).trim() !== '' && String(c.server.gpu_layers).trim() !== 'default') {
      cmd.push('-ngl', String(c.server.gpu_layers).trim());
    }
    if (c.server.main_gpu !== undefined && c.server.main_gpu !== null && Number(c.server.main_gpu) > 0) {
      cmd.push('-mg', String(c.server.main_gpu));
    }
    if (c.server.split_mode && c.server.split_mode !== 'auto' && c.server.split_mode !== 'default' && c.server.split_mode !== 'layer') {
      cmd.push('-sm', c.server.split_mode);
    }
    if (c.server.load_mode && c.server.load_mode !== 'auto' && c.server.load_mode !== 'default') {
      cmd.push('-lm', c.server.load_mode);
    }
    if (c.server.fit === false || String(c.server.fit) === 'off') {
      cmd.push('--no-fit');
    } else if (c.server.fit === true || String(c.server.fit) === 'on') {
      cmd.push('--fit');
    }

    // Context & Compute
    if (c.server.context_size !== undefined && c.server.context_size !== null && String(c.server.context_size).trim() !== '' && Number(c.server.context_size) > 0) {
      cmd.push('-c', String(c.server.context_size).trim());
    }
    if (c.server.max_tokens !== undefined && c.server.max_tokens !== null && String(c.server.max_tokens).trim() !== '' && Number(c.server.max_tokens) > 0) {
      cmd.push('-n', String(c.server.max_tokens).trim());
    }
    if (c.server.threads !== undefined && c.server.threads !== null && String(c.server.threads).trim() !== '' && Number(c.server.threads) > 0) {
      cmd.push('-t', String(c.server.threads).trim());
    }
    if (c.server.threads_batch !== undefined && c.server.threads_batch !== null && String(c.server.threads_batch).trim() !== '' && Number(c.server.threads_batch) > 0) {
      cmd.push('-tb', String(c.server.threads_batch).trim());
    }
    if (c.server.batch_size !== undefined && c.server.batch_size !== null && String(c.server.batch_size).trim() !== '' && Number(c.server.batch_size) > 0) {
      cmd.push('-b', String(c.server.batch_size).trim());
    }
    if (c.server.ubatch_size !== undefined && c.server.ubatch_size !== null && String(c.server.ubatch_size).trim() !== '' && Number(c.server.ubatch_size) > 0) {
      cmd.push('-ub', String(c.server.ubatch_size).trim());
    }
    if (c.server.parallel_slots !== undefined && c.server.parallel_slots !== null && String(c.server.parallel_slots).trim() !== '' && Number(c.server.parallel_slots) > 0) {
      cmd.push('-np', String(c.server.parallel_slots).trim());
    }

    // Performance & KV Cache
    if (c.server.flash_attention === 'on' || String(c.server.flash_attention) === 'true') {
      cmd.push('--flash-attn', 'on');
    } else if (c.server.flash_attention === 'off' || String(c.server.flash_attention) === 'false') {
      cmd.push('--flash-attn', 'off');
    }

    if (c.server.continuous_batching === true || String(c.server.continuous_batching) === 'on' || String(c.server.continuous_batching) === 'true') {
      cmd.push('--cont-batching');
    } else if (c.server.continuous_batching === false || String(c.server.continuous_batching) === 'off' || String(c.server.continuous_batching) === 'false') {
      cmd.push('--no-cont-batching');
    }

    if (c.server.cache_type_k && c.server.cache_type_k !== 'auto' && c.server.cache_type_k !== 'default' && c.server.cache_type_k !== 'f16') {
      cmd.push('-ctk', c.server.cache_type_k);
    }
    if (c.server.cache_type_v && c.server.cache_type_v !== 'auto' && c.server.cache_type_v !== 'default' && c.server.cache_type_v !== 'f16') {
      cmd.push('-ctv', c.server.cache_type_v);
    }
    if (c.server.kv_offload === false || String(c.server.kv_offload) === 'off' || String(c.server.kv_offload) === 'false') {
      cmd.push('--no-kv-offload');
    }
    if (c.server.kv_unified === true || String(c.server.kv_unified) === 'on' || String(c.server.kv_unified) === 'true') {
      cmd.push('-kvu');
    }
    if (c.server.cache_prompt === true || String(c.server.cache_prompt) === 'on' || String(c.server.cache_prompt) === 'true') {
      cmd.push('--cache-prompt');
    } else if (c.server.cache_prompt === false || String(c.server.cache_prompt) === 'off' || String(c.server.cache_prompt) === 'false') {
      cmd.push('--no-cache-prompt');
    }

    // Reasoning & Templates
    if (c.server.reasoning_mode && c.server.reasoning_mode !== 'auto' && c.server.reasoning_mode !== 'default') {
      cmd.push('--reasoning', c.server.reasoning_mode);
    }
    if (c.server.reasoning_format && c.server.reasoning_format !== 'auto' && c.server.reasoning_format !== 'default' && c.server.reasoning_format !== 'none') {
      cmd.push('--reasoning-format', c.server.reasoning_format);
    }
    if (c.server.reasoning_budget !== undefined && c.server.reasoning_budget !== null && String(c.server.reasoning_budget).trim() !== '' && Number(c.server.reasoning_budget) >= 0) {
      cmd.push('--reasoning-budget', String(c.server.reasoning_budget).trim());
    }
    if (c.server.chat_template && c.server.chat_template.trim() !== '' && c.server.chat_template !== 'auto') {
      cmd.push('--chat-template', c.server.chat_template.trim());
    }
    if (c.server.chat_template_file && c.server.chat_template_file.trim() !== '') {
      cmd.push('--chat-template-file', `"${c.server.chat_template_file.trim()}"`);
    }
    if (c.server.chat_template_kwargs && c.server.chat_template_kwargs.trim() !== '') {
      cmd.push('--chat-template-kwargs', `'${c.server.chat_template_kwargs.trim()}'`);
    }
    if (c.server.jinja === true || String(c.server.jinja) === 'on' || String(c.server.jinja) === 'true') {
      cmd.push('--jinja');
    }

    // Sampling Defaults (Only included if filled in!)
    if (c.sampling.temperature !== undefined && c.sampling.temperature !== null && String(c.sampling.temperature).trim() !== '') {
      cmd.push('--temp', String(c.sampling.temperature));
    }
    if (c.sampling.top_k !== undefined && c.sampling.top_k !== null && String(c.sampling.top_k).trim() !== '') {
      cmd.push('--top-k', String(c.sampling.top_k));
    }
    if (c.sampling.top_p !== undefined && c.sampling.top_p !== null && String(c.sampling.top_p).trim() !== '') {
      cmd.push('--top-p', String(c.sampling.top_p));
    }
    if (c.sampling.min_p !== undefined && c.sampling.min_p !== null && String(c.sampling.min_p).trim() !== '') {
      cmd.push('--min-p', String(c.sampling.min_p));
    }
    if (c.sampling.repeat_penalty !== undefined && c.sampling.repeat_penalty !== null && String(c.sampling.repeat_penalty).trim() !== '') {
      cmd.push('--repeat-penalty', String(c.sampling.repeat_penalty));
    }
    if (c.sampling.repeat_last_n !== undefined && c.sampling.repeat_last_n !== null && String(c.sampling.repeat_last_n).trim() !== '' && Number(c.sampling.repeat_last_n) !== 64) {
      cmd.push('--repeat-last-n', String(c.sampling.repeat_last_n));
    }
    if (c.sampling.presence_penalty !== undefined && c.sampling.presence_penalty !== null && String(c.sampling.presence_penalty).trim() !== '' && Number(c.sampling.presence_penalty) !== 0) {
      cmd.push('--presence-penalty', String(c.sampling.presence_penalty));
    }
    if (c.sampling.frequency_penalty !== undefined && c.sampling.frequency_penalty !== null && String(c.sampling.frequency_penalty).trim() !== '' && Number(c.sampling.frequency_penalty) !== 0) {
      cmd.push('--frequency-penalty', String(c.sampling.frequency_penalty));
    }
    if (c.sampling.dry_multiplier !== undefined && c.sampling.dry_multiplier !== null && String(c.sampling.dry_multiplier).trim() !== '' && Number(c.sampling.dry_multiplier) > 0) {
      cmd.push('--dry-multiplier', String(c.sampling.dry_multiplier));
      if (c.sampling.dry_base !== undefined && c.sampling.dry_base !== null && String(c.sampling.dry_base).trim() !== '') {
        cmd.push('--dry-base', String(c.sampling.dry_base));
      }
    }

    if (c.server.advanced && c.server.advanced.trim()) {
      cmd.push(c.server.advanced.trim());
    }

    return cmd.join(' ');
  });

  private pollTimer: any = null;

  constructor() {
    this.init();
  }

  private init() {
    this.refreshStatus();
    this.loadConfig();
    this.loadPresets();
    this.scanModels();
    this.refreshProjects();
    this.startLogsStream();

    this.startAdaptivePolling();

    // Pause / resume polling on tab visibility change to minimize CPU/battery usage
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
          this.startAdaptivePolling(20000); // 20s when backgrounded
        } else {
          this.refreshStatus();
          this.startAdaptivePolling(3000); // 3s when active
        }
      });
    }
  }

  private startAdaptivePolling(intervalMs: number = 3000) {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
    }
    this.pollTimer = setInterval(() => {
      this.refreshStatus();
    }, intervalMs);
  }

  backendConnected = signal<boolean>(true);

  refreshStatus() {
    this.api.getStatus().subscribe({
      next: (status) => {
        const wasDisconnected = !this.backendConnected();
        this.backendConnected.set(true);
        this.serverStatus.set(status);

        if (wasDisconnected) {
          this.loadConfig();
          this.loadPresets();
          this.scanModels();
          this.refreshProjects();
          this.startLogsStream();
        }
      },
      error: () => {
        this.backendConnected.set(false);
        this.serverStatus.set({
          running: false,
          healthy: false,
          pid: null,
          status: 'STOPPED',
          startTime: null,
          uptimeSeconds: 0
        });
      }
    });
  }

  loadConfig() {
    this.api.getLlamaConfig().subscribe({
      next: (cfg) => {
        if (cfg && cfg.paths && cfg.server) {
          this.config.set(cfg);
        }
      }
    });
  }

  saveConfig(cfg?: FullLlamaConfig) {
    const toSave = cfg || this.config();
    this.api.saveLlamaConfig(toSave).subscribe({
      next: (res) => {
        if (res.success) {
          this.showNotification('success', 'Configuration saved successfully!');
        }
      },
      error: (err) => {
        this.showNotification('error', 'Failed to save configuration');
      }
    });
  }

  loadPresets() {
    this.api.getPresets().subscribe({
      next: (presets) => this.presets.set(presets)
    });
  }

  applyPreset(preset: ConfigPreset) {
    this.config.set(JSON.parse(JSON.stringify(preset.config)));
    this.showNotification('info', `Applied preset: ${preset.name}`);
  }

  scanModels(path?: string) {
    this.api.scanModels(path).subscribe({
      next: (res) => {
        const uniqueModels = Array.from(
          new Map((res.models || []).map((m) => [m.path.toLowerCase(), m])).values()
        );
        const uniqueExecutables = Array.from(
          new Map((res.executables || []).map((e) => [e.path.toLowerCase(), e])).values()
        );
        this.scannedModels.set(uniqueModels);
        this.scannedExecutables.set(uniqueExecutables);
      }
    });
  }

  refreshProjects() {
    this.api.listProjects().subscribe({
      next: (projs) => this.projects.set(projs)
    });
  }

  startServer() {
    this.isStarting.set(true);
    this.api.startServer(this.config()).subscribe({
      next: (res) => {
        this.isStarting.set(false);
        this.refreshStatus();
        this.showNotification('success', `Llama server started! PID: ${res.pid}`);
      },
      error: (err) => {
        this.isStarting.set(false);
        this.showNotification('error', err.error?.error || 'Failed to start Llama server');
      }
    });
  }

  stopServer() {
    this.isStopping.set(true);
    this.api.stopServer().subscribe({
      next: (res) => {
        this.isStopping.set(false);
        this.refreshStatus();
        this.showNotification('info', 'Llama server stopped');
      },
      error: (err) => {
        this.isStopping.set(false);
        this.showNotification('error', 'Failed to stop Llama server');
      }
    });
  }

  notifyConfigChanged() {
    this.config.update((c) => ({ ...c }));
  }

  shutdownBackend(keepLlama: boolean = false) {
    this.api.shutdownBackend(keepLlama).subscribe({
      next: (res) => {
        this.backendConnected.set(false);
        if (!keepLlama) {
          this.serverStatus.set({
            running: false,
            healthy: false,
            pid: null,
            status: 'STOPPED',
            startTime: null,
            uptimeSeconds: 0,
            command: ''
          });
          this.showNotification('info', 'Java Backend and Llama server have been stopped.');
        } else {
          this.showNotification('info', 'Java Backend stopped. Llama server continues running in background.');
        }
      },
      error: () => {
        this.backendConnected.set(false);
        this.showNotification('info', 'Java Backend stopped.');
      }
    });
  }

  restartServer() {
    this.isStarting.set(true);
    this.api.restartServer(this.config()).subscribe({
      next: (res) => {
        this.isStarting.set(false);
        this.refreshStatus();
        this.showNotification('success', `Llama server restarted! PID: ${res.pid}`);
      },
      error: (err) => {
        this.isStarting.set(false);
        this.showNotification('error', 'Failed to restart server');
      }
    });
  }

  startLogsStream() {
    if (this.eventSource) {
      this.eventSource.close();
    }
    this.eventSource = this.api.connectLogsSse(
      (log) => {
        this.logs.update((current) => {
          const updated = [...current, log];
          return updated.length > 3000 ? updated.slice(-3000) : updated;
        });
      },
      (err) => {
        // SSE reconnect fallback is automatic in browser
      }
    );
  }

  clearLogs() {
    this.api.clearLogs().subscribe({
      next: () => this.logs.set([])
    });
  }

  showNotification(type: 'success' | 'error' | 'info', message: string) {
    this.notification.set({ type, message });
    setTimeout(() => {
      this.notification.set(null);
    }, 4000);
  }
}
