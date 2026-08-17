import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StateService } from '../../services/state.service';
import { ApiService } from '../../services/api.service';

interface ChatMessage {
  role: 'user' | 'assistant' | 'system';
  content: string;
  reasoning_content?: string;
  elapsedMs?: number;
  timestamp: number;
}

@Component({
  selector: 'app-chat-playground',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-playground.component.html',
  styleUrls: ['./chat-playground.component.scss']
})
export class ChatPlaygroundComponent {
  state = inject(StateService);
  api = inject(ApiService);

  promptText = signal<string>('Write a high-performance Java 21 method with virtual threads.');
  systemPrompt = signal<string>('You are an expert AI software architect and coding assistant.');
  temperature = signal<number>(0.7);
  maxTokens = signal<number>(1024);

  isLoading = signal<boolean>(false);
  messages = signal<ChatMessage[]>([
    {
      role: 'assistant',
      content: 'Hello! I am connected to your local Llama server. Type any prompt below to test completions, latency, and reasoning traces.',
      timestamp: Date.now()
    }
  ]);

  onKeyDown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendPrompt();
    }
  }

  sendPrompt() {
    const text = this.promptText().trim();
    if (!text || this.isLoading()) return;

    if (!this.state.serverStatus().running) {
      this.state.showNotification('error', 'Llama server is not running. Please start it from the top bar first.');
      return;
    }

    const srv = this.state.config().server;

    // Add user message
    this.messages.update(m => [...m, {
      role: 'user',
      content: text,
      timestamp: Date.now()
    }]);

    this.promptText.set('');
    this.isLoading.set(true);

    this.api.testChatCompletion({
      host: srv.host || '127.0.0.1',
      port: srv.port || 9090,
      model: srv.alias || 'qwen-fable',
      prompt: text,
      temperature: this.temperature(),
      max_tokens: this.maxTokens()
    }).subscribe({
      next: (res) => {
        this.isLoading.set(false);
        if (res.error) {
          this.messages.update(m => [...m, {
            role: 'assistant',
            content: `Error from server: ${res.error}`,
            elapsedMs: res.elapsedMs,
            timestamp: Date.now()
          }]);
        } else {
          this.messages.update(m => [...m, {
            role: 'assistant',
            content: res.content || '(Empty response)',
            reasoning_content: res.reasoning_content,
            elapsedMs: res.elapsedMs,
            timestamp: Date.now()
          }]);
        }
      },
      error: (err) => {
        this.isLoading.set(false);
        this.messages.update(m => [...m, {
          role: 'assistant',
          content: `Request failed: ${err.message || 'Server connection error'}`,
          timestamp: Date.now()
        }]);
      }
    });
  }

  clearChat() {
    this.messages.set([]);
  }
}
