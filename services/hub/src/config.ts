export interface HubConfig {
  readonly port: number;
  readonly dataDir: string;
  readonly logLevel: string;
  readonly eventRetentionDays: number;
}

export function loadConfig(env: NodeJS.ProcessEnv): HubConfig {
  const rawPort = env.HUB_PORT ?? "8443";
  const port = Number(rawPort);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`HUB_PORT must be an integer between 1 and 65535, got ${rawPort}`);
  }

  const rawRetention = env.HUB_EVENT_RETENTION_DAYS ?? "30";
  const eventRetentionDays = Number(rawRetention);
  if (!Number.isInteger(eventRetentionDays) || eventRetentionDays < 1) {
    throw new Error(`HUB_EVENT_RETENTION_DAYS must be a positive integer, got ${rawRetention}`);
  }

  return {
    port,
    dataDir: env.HUB_DATA_DIR ?? "./hub-data",
    logLevel: env.HUB_LOG_LEVEL ?? "info",
    eventRetentionDays,
  };
}
