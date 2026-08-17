import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { FullLlamaConfig, ServerStatus, LogEntry, ScannedFile, ConfigPreset } from '../models/llama-config.model';
import { ProjectInfo, CreateProjectRequest, OpenCodeConfig } from '../models/project.model';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private http = inject(HttpClient);
  private baseUrl = typeof window !== 'undefined' ? `${window.location.protocol}//${window.location.host}/api` : '/api';

  // --- Server Lifecycle & Status ---

  getStatus(): Observable<ServerStatus> {
    return this.http.get<ServerStatus>(`${this.baseUrl}/status`);
  }

  startServer(config: FullLlamaConfig): Observable<{ success: boolean; pid: number; command: string }> {
    return this.http.post<{ success: boolean; pid: number; command: string }>(`${this.baseUrl}/server/start`, config);
  }

  stopServer(): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(`${this.baseUrl}/server/stop`, {});
  }

  shutdownBackend(keepLlama: boolean = false): Observable<{ success: boolean; message: string; keepLlama?: boolean }> {
    return this.http.post<{ success: boolean; message: string; keepLlama?: boolean }>(`${this.baseUrl}/backend/shutdown`, { keepLlama });
  }

  restartServer(config: FullLlamaConfig): Observable<{ success: boolean; pid: number; command: string }> {
    return this.http.post<{ success: boolean; pid: number; command: string }>(`${this.baseUrl}/server/restart`, config);
  }

  // --- Server Config & Presets ---

  getLlamaConfig(): Observable<FullLlamaConfig> {
    return this.http.get<FullLlamaConfig>(`${this.baseUrl}/config/llama`);
  }

  saveLlamaConfig(config: FullLlamaConfig): Observable<{ success: boolean; config: FullLlamaConfig }> {
    return this.http.post<{ success: boolean; config: FullLlamaConfig }>(`${this.baseUrl}/config/llama`, config);
  }

  getPresets(): Observable<ConfigPreset[]> {
    return this.http.get<ConfigPreset[]>(`${this.baseUrl}/config/llama/presets`);
  }

  // --- Model and Executable Scanning ---

  scanModels(path?: string): Observable<{ models: ScannedFile[]; executables: ScannedFile[] }> {
    const url = path ? `${this.baseUrl}/models/scan?path=${encodeURIComponent(path)}` : `${this.baseUrl}/models/scan`;
    return this.http.get<{ models: ScannedFile[]; executables: ScannedFile[] }>(url);
  }

  getSystemPaths(): Observable<any> {
    return this.http.get<any>(`${this.baseUrl}/system/paths`);
  }

  // --- Logs & Streaming ---

  connectLogsSse(onMessage: (log: LogEntry) => void, onError?: (err: any) => void): EventSource {
    const eventSource = new EventSource(`${this.baseUrl}/server/logs`);
    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch (e) {
        // Ignored
      }
    };
    if (onError) {
      eventSource.onerror = onError;
    }
    return eventSource;
  }

  pollLogs(sinceId: number = 0): Observable<LogEntry[]> {
    return this.http.get<LogEntry[]>(`${this.baseUrl}/server/logs/poll?since=${sinceId}`);
  }

  clearLogs(): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${this.baseUrl}/server/logs/clear`, {});
  }

  // --- Pi Coding Agent Projects Management ---

  listProjects(baseDir?: string): Observable<ProjectInfo[]> {
    const url = baseDir ? `${this.baseUrl}/projects/list?baseDir=${encodeURIComponent(baseDir)}` : `${this.baseUrl}/projects/list`;
    return this.http.get<ProjectInfo[]>(url);
  }

  createProject(req: CreateProjectRequest): Observable<{ success: boolean; projectName: string; projectPath: string; configPath: string }> {
    return this.http.post<{ success: boolean; projectName: string; projectPath: string; configPath: string }>(`${this.baseUrl}/projects/create`, req);
  }

  injectPiConfig(projectPath: string, config: any): Observable<{ success: boolean; configPath: string }> {
    return this.http.post<{ success: boolean; configPath: string }>(`${this.baseUrl}/projects/pi/inject`, {
      projectPath,
      piModelsConfig: config
    });
  }

  injectOpenCodeConfig(projectPath: string, config: any): Observable<{ success: boolean; configPath: string }> {
    return this.injectPiConfig(projectPath, config);
  }

  startPiTerminal(projectPath: string, terminalType: 'wt' | 'powershell' | 'cmd' = 'wt'): Observable<{ success: boolean; message: string }> {
    return this.http.post<{ success: boolean; message: string }>(`${this.baseUrl}/projects/pi/start`, {
      projectPath,
      terminalType
    });
  }

  startOpenCodeTerminal(projectPath: string, terminalType: 'wt' | 'powershell' | 'cmd' = 'wt'): Observable<{ success: boolean; message: string }> {
    return this.startPiTerminal(projectPath, terminalType);
  }

  openInExplorer(projectPath: string): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${this.baseUrl}/projects/open-explorer`, { projectPath });
  }

  openInVsCode(projectPath: string): Observable<{ success: boolean }> {
    return this.http.post<{ success: boolean }>(`${this.baseUrl}/projects/open-vscode`, { projectPath });
  }

  // --- Chat Playground / API Testing ---

  testChatCompletion(params: {
    host: string;
    port: number;
    model: string;
    prompt: string;
    temperature?: number;
    max_tokens?: number;
  }): Observable<{
    statusCode: number;
    elapsedMs: number;
    content?: string;
    reasoning_content?: string;
    usage?: any;
    error?: string;
    raw?: string;
  }> {
    return this.http.post<any>(`${this.baseUrl}/chat/test`, params);
  }
}
