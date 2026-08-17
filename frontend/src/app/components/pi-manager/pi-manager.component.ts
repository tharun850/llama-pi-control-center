import { Component, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { StateService } from '../../services/state.service';
import { ApiService } from '../../services/api.service';
import { ProjectInfo, PiModelsConfig, CreateProjectRequest } from '../../models/project.model';

@Component({
  selector: 'app-pi-manager',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './pi-manager.component.html',
  styleUrls: ['./pi-manager.component.scss']
})
export class PiManagerComponent {
  state = inject(StateService);
  private api = inject(ApiService);

  newProjectName = 'my-pi-project';
  baseDir = 'C:\\AI\\Projects';
  customContextLimit = 32768;
  customOutputLimit = 8192;
  thinkingLevel = 'high';
  sampleType: 'java' | 'node' | 'none' = 'java';
  terminalType: 'wt' | 'powershell' | 'cmd' = 'wt';

  isCreating = signal<boolean>(false);
  isInjecting = signal<string | null>(null);
  selectedProjectForEdit = signal<ProjectInfo | null>(null);
  editedConfigJson = signal<string>('');

  // Real-time generated .pi/models.json preview
  generatedPiModelsConfig = computed<PiModelsConfig>(() => {
    const alias = this.state.config().server.alias || 'qwen3.8';
    const port = this.state.config().server.port || 9090;
    const host = this.state.config().server.host || '127.0.0.1';

    return {
      providers: {
        'llama': {
          name: 'llama.cpp (local)',
          baseUrl: `http://${host}:${port}/v1`,
          api: 'openai-completions',
          apiKey: 'no-key',
          compat: {
            supportsDeveloperRole: false,
            supportsReasoningEffort: false
          },
          models: [
            {
              id: alias,
              name: alias,
              contextWindow: this.customContextLimit,
              maxTokens: this.customOutputLimit,
              reasoning: true
            }
          ]
        }
      }
    };
  });

  get generatedPiModelsJsonString(): string {
    return JSON.stringify(this.generatedPiModelsConfig(), null, 2);
  }

  createProject() {
    if (!this.newProjectName.trim()) {
      this.state.showNotification('error', 'Please enter a valid project name');
      return;
    }

    this.isCreating.set(true);
    const req: CreateProjectRequest = {
      projectName: this.newProjectName.trim(),
      baseDir: this.baseDir.trim(),
      piModelsConfig: this.generatedPiModelsConfig(),
      createSampleFile: this.sampleType !== 'none',
      sampleType: this.sampleType
    };

    this.api.createProject(req).subscribe({
      next: (res) => {
        this.isCreating.set(false);
        this.state.refreshProjects();
        this.state.showNotification('success', `Pi project "${res.projectName}" created with .pi/models.json!`);
      },
      error: (err) => {
        this.isCreating.set(false);
        this.state.showNotification('error', err.error?.error || 'Failed to create Pi project');
      }
    });
  }

  startPi(project: ProjectInfo) {
    this.api.startPiTerminal(project.path, this.terminalType).subscribe({
      next: () => {
        this.state.showNotification('success', `Launched Pi Coding Agent terminal in ${project.name}`);
      },
      error: (err) => {
        this.state.showNotification('error', 'Failed to launch Pi terminal');
      }
    });
  }

  syncPiConfig(project: ProjectInfo) {
    this.isInjecting.set(project.path);
    const cfg = this.generatedPiModelsConfig();

    this.api.injectPiConfig(project.path, cfg).subscribe({
      next: () => {
        this.isInjecting.set(null);
        this.state.refreshProjects();
        this.state.showNotification('success', `Synced .pi/models.json in ${project.name}`);
      },
      error: () => {
        this.isInjecting.set(null);
        this.state.showNotification('error', 'Failed to sync Pi config');
      }
    });
  }

  openExplorer(project: ProjectInfo) {
    this.api.openInExplorer(project.path).subscribe();
  }

  openVsCode(project: ProjectInfo) {
    this.api.openInVsCode(project.path).subscribe({
      error: () => this.state.showNotification('error', 'Make sure VS Code is in your system PATH ("code")')
    });
  }

  openConfigEditor(project: ProjectInfo) {
    this.selectedProjectForEdit.set(project);
    this.editedConfigJson.set(project.piModelsRaw || this.generatedPiModelsJsonString);
  }

  closeConfigEditor() {
    this.selectedProjectForEdit.set(null);
  }

  saveEditedConfig() {
    const proj = this.selectedProjectForEdit();
    if (!proj) return;

    try {
      const parsed = JSON.parse(this.editedConfigJson());
      this.api.injectPiConfig(proj.path, parsed).subscribe({
        next: () => {
          this.closeConfigEditor();
          this.state.refreshProjects();
          this.state.showNotification('success', 'Pi configuration saved successfully!');
        },
        error: (err) => {
          this.state.showNotification('error', err.error?.error || 'Failed to save config');
        }
      });
    } catch (e: any) {
      this.state.showNotification('error', 'Invalid JSON syntax: ' + e.message);
    }
  }
}
