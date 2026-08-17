export interface PiModel {
  id: string;
  name?: string;
  contextWindow?: number;
  maxTokens?: number;
  reasoning?: boolean;
}

export interface PiProvider {
  name?: string;
  baseUrl: string;
  api: string;
  apiKey?: string;
  compat?: {
    supportsDeveloperRole?: boolean;
    supportsReasoningEffort?: boolean;
  };
  models: PiModel[];
}

export interface PiModelsConfig {
  providers: {
    [providerId: string]: PiProvider;
  };
}

export interface PiSettingsConfig {
  defaultProvider?: string;
  defaultModel?: string;
  thinking?: string;
  theme?: string;
}

// Backward compatibility types
export interface OpenCodeConfig {
  $schema?: string;
  model: string;
  provider?: any;
}

export interface ProjectInfo {
  name: string;
  path: string;
  lastModified: number;
  hasPiConfig: boolean;
  hasOpenCodeConfig?: boolean;
  piModelsConfig?: PiModelsConfig;
  piModelsRaw?: string;
  piSettingsRaw?: string;
  opencodeConfig?: OpenCodeConfig;
  opencodeConfigRaw?: string;
}

export interface CreateProjectRequest {
  projectName: string;
  baseDir?: string;
  piModelsConfig?: PiModelsConfig | string;
  piSettingsConfig?: PiSettingsConfig | string;
  opencodeConfig?: any;
  createSampleFile?: boolean;
  sampleType?: 'java' | 'node' | 'none';
}
