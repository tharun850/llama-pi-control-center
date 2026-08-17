import { Component, inject, signal, computed, effect, ViewChild, ElementRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StateService } from '../../services/state.service';
import { LogEntry } from '../../models/llama-config.model';

@Component({
  selector: 'app-logs-viewer',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './logs-viewer.component.html',
  styleUrls: ['./logs-viewer.component.scss']
})
export class LogsViewerComponent {
  state = inject(StateService);

  @ViewChild('terminalContainer') terminalRef!: ElementRef<HTMLDivElement>;

  searchQuery = signal<string>('');
  selectedLevel = signal<string>('ALL');
  autoScroll = signal<boolean>(true);
  copied = signal<boolean>(false);

  filteredLogs = computed(() => {
    const logs = this.state.logs();
    const query = this.searchQuery().toLowerCase().trim();
    const level = this.selectedLevel();

    return logs.filter((log) => {
      const matchLevel = level === 'ALL' || log.level === level;
      const matchQuery = !query || log.message.toLowerCase().includes(query) || log.level.toLowerCase().includes(query);
      return matchLevel && matchQuery;
    });
  });

  constructor() {
    effect(() => {
      // Whenever filteredLogs change, auto-scroll if enabled
      this.filteredLogs();
      if (this.autoScroll()) {
        setTimeout(() => this.scrollToBottom(), 50);
      }
    });
  }

  scrollToBottom() {
    if (this.terminalRef && this.terminalRef.nativeElement) {
      const el = this.terminalRef.nativeElement;
      el.scrollTop = el.scrollHeight;
    }
  }

  formatTime(timestamp: number): string {
    const d = new Date(timestamp);
    return d.toTimeString().split(' ')[0] + '.' + String(d.getMilliseconds()).padStart(3, '0');
  }

  copyAllLogs() {
    const text = this.state.logs().map(l => `[${this.formatTime(l.timestamp)}] [${l.level}] ${l.message}`).join('\n');
    navigator.clipboard.writeText(text).then(() => {
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    });
  }

  downloadLogs() {
    const text = this.state.logs().map(l => `[${this.formatTime(l.timestamp)}] [${l.level}] ${l.message}`).join('\n');
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `llama-server-logs-${new Date().toISOString().replace(/[:.]/g, '-')}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  }
}
