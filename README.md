# Llama Server and Pi Control Center

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://www.oracle.com/java/)
[![Angular](https://img.shields.io/badge/Angular-21.0-DD0031?style=for-the-badge&logo=angular&logoColor=white)](https://angular.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.0-3178C6?style=for-the-badge&logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![llama.cpp](https://img.shields.io/badge/llama.cpp-Server-00599C?style=for-the-badge&logo=c%2B%2B&logoColor=white)](https://github.com/ggerganov/llama.cpp)
[![Pi Agent](https://img.shields.io/badge/Pi%20Agent-Coding-FF6F00?style=for-the-badge&logo=npm&logoColor=white)](https://www.npmjs.com/package/@earendil-works/pi-coding-agent)
[![License](https://img.shields.io/badge/License-MIT-green?style=for-the-badge)](LICENSE)

A lightweight desktop control center and process orchestrator for hosting local Large Language Models with **llama.cpp** and automating **Pi Coding Agent** workspaces.

Built with **Angular 21 (Zoneless)** and an **embedded, zero-dependency Java 21 backend**, this application combines model parameter management, GPU offloading controls, live Server-Sent Events (SSE) log streaming, and workspace generation into a single web dashboard.

---

## Architecture

The project runs on a **Unified Single-Port Architecture**: the Java 21 backend directly serves the compiled Angular 21 Single Page Application (`dist/frontend`) and all REST/SSE API endpoints on a single port (**`http://127.0.0.1:8765`**). No separate Node.js or Angular development server is required during normal runtime.

```
                      +------------------------------------------+
                      |         Web Browser / Dashboard          |
                      |          http://127.0.0.1:8765           |
                      +--------------------+---------------------+
                                           |
                                           | HTTP / REST / SSE
                                           v
+---------------------------------------------------------------------------------+
|                    Unified Java 21 Backend (Port 8765)                          |
|                                                                                 |
|  [ Static File Server ]  --> Serves compiled Angular 21 UI (HTML/CSS/JS)        |
|  [ REST API Handlers ]   --> /api/config, /api/start, /api/stop, /api/status   |
|  [ SSE Log Streamer ]    --> /api/logs/stream (Real-time console logs)          |
|  [ Project Manager ]     --> /api/projects/* (Auto-generates .pi/models.json)   |
|  [ Process Supervisor ]  --> Manages llama-server.exe child process lifecycle   |
+------------------------------------+--------------------+-----------------------+
                                     |                    |
                  Process Management |                    | Workspace Automation
                                     v                    v
         +---------------------------------------+    +----------------------------------+
         |     llama.cpp Server (Port 9090)      |    |      Pi Coding Agent Workspaces  |
         |                                       |    |                                  |
         | - Model Weights (GGUF)                |    | - C:\AI\Projects\<project-name>  |
         | - GPU Offload (CUDA/Vulkan/Metal)     |    | - Auto .pi/models.json config    |
         | - OpenAI-Compatible /v1 API           |    | - 1-Click Terminal / VS Code     |
         +---------------------------------------+    +----------------------------------+
```

---

## Features

### 1. Llama Server Configuration
- **Full CLI Flag Mapping**: Configure all parameters exposed by `llama-server.exe` through a clean user interface.
- **Hardware and GPU Offload**: Multi-GPU split modes (`layer`, `row`, `tensor`), GPU layer count (`-ngl`), main GPU index selection, `mmap`, `mlock`, and direct I/O.
- **Context and Compute**: Context size (`-c`), batch size (`-b`), micro-batch size (`-ub`), CPU generation and batch threads (`-t`, `-tb`).
- **KV Cache Optimization**: Quantized KV cache data types (`f16`, `bf16`, `q8_0`, `q4_0`, `iq4_nl`, `q5_0`), Flash Attention (`-fa`), prompt caching, and continuous batching (`-cb`).
- **Reasoning and Chat Templates**: Reasoning mode toggles (`on`, `off`, `auto`), reasoning output format (`deepseek`, `none`), token budgets, and built-in Jinja chat templates (Qwen, DeepSeek R1/V3, Llama 3, Mistral, Gemma, Phi, Command-R, etc.).
- **Live Command Line Preview**: Displays the exact command string in real-time as fields are modified.

### 2. Process Lifecycle and Health Monitoring
- **Start, Stop, and Restart**: Spawns and terminates the `llama-server.exe` process tree directly from the UI.
- **Backend Status Monitor**: Real-time status badge showing backend connectivity and process state.
- **Stop Backend Button**: Cleanly shuts down the Java server and its active children from the header navigation.
- **Configuration Presets**: 1-click presets for common workloads:
  - Fast 8K Coding
  - 32K Deep Reasoning
  - Low VRAM / Partial CPU
  - High Concurrency (4 Slots)

### 3. Live Log Streamer
- **Server-Sent Events (SSE)**: Stream standard output and standard error in real-time.
- **Log Level Filtering**: Filter output by `INFO`, `WARN`, `ERROR`, `SLOT`, and `SYSTEM`.
- **Search and Export**: In-terminal search query filter, copy to clipboard, or export logs as a text file.

### 4. Pi Coding Agent Workspace Manager
- **Project Wizard**: Create and initialize workspaces in your projects directory with optional starter files (`Main.java`, `index.js`, etc.).
- **Automated Configuration**: Generates `.pi/models.json` mapped to the active local server port and model alias.
- **Global Settings Sync**: Syncs `~/.pi/agent/settings.json` and `~/.pi/agent/auth.json` so the `pi` command line interface works out of the box.
- **Launch Actions**:
  - Start Pi: Opens an interactive terminal session in Windows Terminal, PowerShell, or Command Prompt.
  - VS Code: Opens the project folder in Visual Studio Code.
  - Explorer: Reveals the directory in Windows File Explorer.
  - Config Editor: In-modal JSON viewer/editor for `.pi/models.json`.

### 5. Chat Playground
- Test model completions directly from the browser.
- Real-time token streaming with throughput metrics (tokens/second, time to first token).
- Collapsible reasoning trace (`<think>` blocks) for reasoning models.

---

## Prerequisites

Before running the application, make sure the following dependencies are installed on your system:

1. **Java Development Kit (JDK 21+)**
   - Verify with: `java -version`
2. **Node.js (v18+) and npm** (required for building the frontend)
   - Verify with: `node -v` and `npm -v`
3. **llama.cpp Executable**
   - Download `llama-server.exe` from [llama.cpp Releases](https://github.com/ggerganov/llama.cpp/releases).
4. **GGUF Model File**
   - Any quantized `.gguf` model file on local storage.
5. **(Optional) Pi Coding Agent**
   - Install globally via: `npm install -g @earendil-works/pi-coding-agent`

---

## Quick Start

### Starting the Application (Windows)
Run `start.bat` or use the PowerShell script:

```cmd
start.bat
```

Or in PowerShell:
```powershell
.\start.ps1
```

This starts the Java server on port **8765** and opens **`http://127.0.0.1:8765`** in your default browser.

---

## Building from Source

### Build Script
Run `build.bat` from the root directory:
```cmd
build.bat
```
This builds the Angular frontend into `frontend/dist/frontend/browser` and compiles `BackendServer.java`.

### Build with Maven
```bash
mvn clean package
```
To run the resulting JAR:
```bash
java -jar target/llama-pi-control-center-1.0.0.jar
```

---

## Project Structure

```
localConfig/
│
├── backend/
│   ├── BackendServer.java          # Java 21 Unified HTTP and SSE Server
│   └── start-backend.bat           # Standalone backend launcher
│
├── frontend/                       # Angular 21 Zoneless Frontend
│   ├── src/
│   │   ├── app/
│   │   │   ├── components/
│   │   │   │   ├── header/         # Top navigation bar and status indicators
│   │   │   │   ├── llama-config/   # Llama server parameter forms and CLI preview
│   │   │   │   ├── pi-manager/     # Pi project wizard and workspace launcher
│   │   │   │   ├── logs-viewer/    # Real-time SSE streaming terminal
│   │   │   │   └── chat-playground/# Interactive prompt test interface
│   │   │   ├── models/             # TypeScript data models and interfaces
│   │   │   └── services/           # StateService (Signals) and API Service
│   │   └── styles.scss             # Dark dashboard theme styles
│   ├── angular.json
│   └── package.json
│
├── .gitignore                      # Git ignore rules
├── build.bat                       # Build script (Angular + Java)
├── create_desktop_shortcut.ps1     # Creates Windows Desktop shortcut
├── llama_config.json               # Persisted Llama server configuration
├── pom.xml                         # Maven build definition
├── README.md                       # Documentation
├── start.bat                       # Windows launcher
└── start.ps1                       # PowerShell launcher
```

---

## API Reference

The Java backend exposes the following REST and SSE endpoints on port `8765`:

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/status` | Current server state (`STOPPED`, `STARTING`, `RUNNING`, `ERROR`), PID, uptime, and active CLI command |
| `GET` | `/api/config` | Load saved `llama_config.json` |
| `POST` | `/api/config` | Save updated `llama_config.json` |
| `POST` | `/api/start` | Launch `llama-server.exe` with active configuration |
| `POST` | `/api/stop` | Terminate running `llama-server.exe` process tree |
| `GET` | `/api/logs` | Retrieve buffered log entries |
| `GET` | `/api/logs/stream` | Real-time Server-Sent Events (SSE) log stream |
| `POST` | `/api/chat/test` | Proxy chat test prompt to active Llama server |
| `GET` | `/api/projects` | Scan and list workspace projects |
| `POST` | `/api/projects/create` | Create new project folder and inject `.pi/models.json` |
| `POST` | `/api/projects/pi/inject`| Sync `.pi/models.json` in an existing project |
| `POST` | `/api/projects/pi/start` | Launch interactive terminal with Pi session |
| `POST` | `/api/projects/open-vscode` | Open project in Visual Studio Code |
| `POST` | `/api/projects/open-explorer` | Open project in Windows File Explorer |
| `POST` | `/api/server/stop` | Gracefully shut down Java backend and children |

---

## Pi Coding Agent Integration

When a project is created or synced, the Control Center writes the following `.pi/models.json` configuration:

```json
{
  "providers": {
    "llama": {
      "name": "llama.cpp (local)",
      "baseUrl": "http://127.0.0.1:9090/v1",
      "api": "openai-completions",
      "apiKey": "no-key",
      "compat": {
        "supportsDeveloperRole": false,
        "supportsReasoningEffort": false
      },
      "models": [
        {
          "id": "qwen3.8",
          "name": "qwen3.8",
          "contextWindow": 32768,
          "maxTokens": 8192,
          "reasoning": true
        }
      ]
    }
  }
}
```

This configuration ensures compatibility with `@earendil-works/pi-coding-agent` without triggering built-in router mode checks.

---

## Troubleshooting

### Port Already in Use
If port 8765 or 9090 is in use, terminate the corresponding process in PowerShell:

```powershell
# Stop process on port 8765 (Backend)
Get-NetTCPConnection -LocalPort 8765 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# Stop process on port 9090 (Llama Server)
Get-NetTCPConnection -LocalPort 9090 -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### PowerShell Script Execution Policy
If PowerShell scripts fail to run due to execution policy restrictions:

```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

---

## License

This project is licensed under the [MIT License](LICENSE).
