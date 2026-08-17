import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { StateService } from '../../services/state.service';
import { ConfigPreset } from '../../models/llama-config.model';

@Component({
  selector: 'app-header',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './header.component.html',
  styleUrls: ['./header.component.scss']
})
export class HeaderComponent {
  state = inject(StateService);

  showShutdownModal = signal<boolean>(false);
  copiedCommand = signal<boolean>(false);

  formatUptime(seconds: number): string {
    if (!seconds || seconds <= 0) return '0s';
    const hrs = Math.floor(seconds / 3600);
    const mins = Math.floor((seconds % 3600) / 60);
    const secs = seconds % 60;
    if (hrs > 0) return `${hrs}h ${mins}m ${secs}s`;
    if (mins > 0) return `${mins}m ${secs}s`;
    return `${secs}s`;
  }

  onPresetChange(event: Event) {
    const target = event.target as HTMLSelectElement;
    const presetId = target.value;
    const preset = this.state.presets().find(p => p.id === presetId);
    if (preset) {
      this.state.applyPreset(preset);
      target.value = '';
    }
  }

  onStopBackendClick() {
    if (this.state.serverStatus().running) {
      this.showShutdownModal.set(true);
    } else {
      if (confirm('Are you sure you want to stop the Java Backend server?')) {
        this.state.shutdownBackend(false);
      }
    }
  }

  confirmShutdown(keepLlama: boolean) {
    this.showShutdownModal.set(false);
    this.state.shutdownBackend(keepLlama);
  }

  closeShutdownModal() {
    this.showShutdownModal.set(false);
  }

  get llamaKillCommand(): string {
    const pid = this.state.serverStatus().pid;
    return pid ? `taskkill /F /PID ${pid}` : `taskkill /IM llama-server.exe /F`;
  }

  copyKillCommand() {
    navigator.clipboard.writeText(this.llamaKillCommand).then(() => {
      this.copiedCommand.set(true);
      setTimeout(() => this.copiedCommand.set(false), 2500);
    });
  }
}
