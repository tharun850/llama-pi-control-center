import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StateService } from '../../services/state.service';
import { FullLlamaConfig, ScannedFile } from '../../models/llama-config.model';

@Component({
  selector: 'app-llama-config',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './llama-config.component.html',
  styleUrls: ['./llama-config.component.scss']
})
export class LlamaConfigComponent {
  state = inject(StateService);

  activeSubTab = signal<'general' | 'hardware' | 'context' | 'network' | 'kvcache' | 'reasoning' | 'sampling' | 'speculative' | 'tools'>('general');

  copied = signal<boolean>(false);
  showModelPicker = signal<boolean>(false);
  showExePicker = signal<boolean>(false);

  // Chat templates list with explanations
  chatTemplates = [
    { id: '', name: 'Auto — Extract template directly from GGUF metadata', desc: 'Uses the Jinja template embedded in the model file' },
    { id: 'chatml', name: 'ChatML (<|im_start|>) — Qwen, Yi, StarCoder, Hermez', desc: 'Standard OpenAI-like format with im_start and im_end tokens' },
    { id: 'deepseek3', name: 'DeepSeek R1 / V3 — Thinking trace & Tool parser', desc: 'Supports thought separation and structured output' },
    { id: 'deepseek', name: 'DeepSeek V2 / Coder — Standard DeepSeek format', desc: 'For DeepSeek Coder and older DeepSeek models' },
    { id: 'llama3', name: 'Llama 3 / 3.1 / 3.2 / 3.3 — Official Meta format', desc: 'Includes start_header_id, end_header_id, and eot_id' },
    { id: 'llama2', name: 'Llama 2 — [INST] <<SYS>> format', desc: 'Classic Llama 2 instruction template' },
    { id: 'mistral-v3', name: 'Mistral v3 / Codestral — [INST] format with tool calls', desc: 'Official Mistral v3 and Codestral 22B template' },
    { id: 'mistral-v7-tekken', name: 'Mistral Large 2 / Tekken tokenizer format', desc: 'Optimized for Tekken 128k tokenizer' },
    { id: 'gemma', name: 'Google Gemma / Gemma 2 — <start_of_turn> format', desc: 'Official template for Gemma 2B, 7B, 9B, 27B' },
    { id: 'phi3', name: 'Microsoft Phi 3 / 3.5 — <|user|> / <|assistant|>', desc: 'Official Phi 3 Mini / Medium template' },
    { id: 'phi4', name: 'Microsoft Phi 4 — Latest Phi 4 multi-turn template', desc: 'Optimized for Phi 4 14B reasoning' },
    { id: 'command-r', name: 'Cohere Command-R / Command-R+', desc: 'Specialized for RAG, citations, and tools' },
    { id: 'granite', name: 'IBM Granite / Granite 4.0 Code & Instruct', desc: 'Optimized for enterprise coding tasks' },
    { id: 'openchat', name: 'OpenChat — GPT4 Correct format', desc: 'GPT4 Correct User/Assistant template' },
    { id: 'vicuna', name: 'Vicuna — USER: / ASSISTANT: format', desc: 'Classic Vicuna instruction format' },
    { id: 'zephyr', name: 'Zephyr — <|user|> / <|assistant|> format', desc: 'HuggingFace Zephyr instruction format' },
    { id: 'bailing-think', name: 'Bailing Think — Preserved thinking template', desc: 'Compatible with continuous thinking traces' }
  ];

  selectModel(file: ScannedFile) {
    const cfg = this.state.config();
    cfg.paths.model_path = file.path;

    // Auto infer alias from model filename
    const baseName = file.name.replace(/\.gguf$/i, '').toLowerCase();
    if (!cfg.server.alias || cfg.server.alias === 'qwen-fable' || cfg.server.alias === 'model') {
      cfg.server.alias = baseName;
    }

    this.state.config.set({ ...cfg });
    this.showModelPicker.set(false);
    this.state.showNotification('info', `Selected model: ${file.name}`);
  }

  selectExe(file: ScannedFile) {
    const cfg = this.state.config();
    cfg.paths.exe_path = file.path;
    this.state.config.set({ ...cfg });
    this.showExePicker.set(false);
    this.state.showNotification('info', `Selected executable: ${file.name}`);
  }

  copyCommand() {
    const cmd = this.state.generatedCommand();
    navigator.clipboard.writeText(cmd).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }

  saveConfig() {
    this.state.saveConfig();
  }

  rescanFiles() {
    this.state.scanModels();
    this.state.showNotification('info', 'Scanning directory for models and binaries...');
  }

  onConfigInput() {
    this.state.notifyConfigChanged();
  }

  clearAllSamplingDefaults() {
    const cfg = this.state.config();
    cfg.sampling = {
      temperature: '' as any,
      top_k: '' as any,
      top_p: '' as any,
      min_p: '' as any,
      repeat_penalty: '' as any,
      repeat_last_n: '' as any,
      presence_penalty: 0,
      frequency_penalty: 0,
      dry_multiplier: 0,
      dry_base: '' as any,
      samplers: ''
    };
    this.state.config.set({ ...cfg });
    this.state.showNotification('info', 'Cleared all sampling flags. The model will use llama.cpp server defaults.');
  }

  resetCodingSampling() {
    const cfg = this.state.config();
    cfg.sampling = {
      temperature: 0.2,
      top_k: 40,
      top_p: 0.95,
      min_p: 0.05,
      repeat_penalty: 1.05,
      repeat_last_n: 64,
      presence_penalty: 0.0,
      frequency_penalty: 0.0,
      dry_multiplier: 0.0,
      dry_base: 1.75,
      samplers: ''
    };
    this.state.config.set({ ...cfg });
    this.state.showNotification('info', 'Reset sampling to low-temperature Coding defaults (Temp: 0.2, Top-P: 0.95, Min-P: 0.05).');
  }
}
