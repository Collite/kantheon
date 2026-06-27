import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';
import { telemetry } from '../main';
import { SeverityNumber } from '@opentelemetry/api-logs';
import { useAuthStore } from '../stores/auth';

const telemetryInstance = telemetry;

export class MCPClientService {
  private client: Client | null = null;
  private transport: StreamableHTTPClientTransport | null = null;

  async connect(url: string) {
    if (this.client) {
      return
    }

    if (telemetryInstance?.logger) {
        telemetryInstance.logger.emit({
            severityNumber: SeverityNumber.INFO,
            severityText: 'INFO',
            body: 'MCP client connecting',
            attributes: { url }
        });
    }

    const authStore = useAuthStore();
    let requestInit: RequestInit | undefined;

    if (authStore.isAuthenticated) {
        await authStore.updateToken(5);
        if (authStore.token) {
            requestInit = {
                headers: {
                    'Authorization': `Bearer ${authStore.token}`
                }
            };
        }
    }

    this.transport = new StreamableHTTPClientTransport(new URL(url), requestInit ? { requestInit } : undefined);
    this.client = new Client({
      name: "agents-mcp-inspector",
      version: "1.0.0"
    }, {
      capabilities: {}
    });

    await this.client.connect(this.transport);

    if (telemetryInstance?.logger) {
        telemetryInstance.logger.emit({
            severityNumber: SeverityNumber.INFO,
            severityText: 'INFO',
            body: 'MCP client connected',
            attributes: { url }
        });
    }
  }

  async disconnect() {
    if (this.client) {
      if (telemetryInstance?.logger) {
          telemetryInstance.logger.emit({
              severityNumber: SeverityNumber.INFO,
              severityText: 'INFO',
              body: 'MCP client disconnecting'
          });
      }
      await this.client.close();
      this.client = null;
    }
    this.transport = null;
  }

  async listTools() {
    if (!this.client) throw new Error("Not connected");
    return await this.client.listTools();
  }

  async callTool(name: string, args: any) {
    if (!this.client) throw new Error("Not connected");

    const startTime = Date.now();

    if (telemetryInstance?.logger) {
        telemetryInstance.logger.emit({
            severityNumber: SeverityNumber.INFO,
            severityText: 'INFO',
            body: 'MCP tool call',
            attributes: { tool: name, args: JSON.stringify(args) }
        });
    }

    try {
      const result = await this.client.callTool({
        name,
        arguments: args
      });

      const duration = (Date.now() - startTime) / 1000;

      if (telemetryInstance?.logger) {
          telemetryInstance.logger.emit({
              severityNumber: SeverityNumber.INFO,
              severityText: 'INFO',
              body: 'MCP tool call completed',
              attributes: {
                  tool: name,
                  durationSeconds: duration,
                  isError: !!result.isError
              }
          });
      }

      return result;
    } catch (error: any) {
      if (telemetryInstance?.logger) {
          telemetryInstance.logger.emit({
              severityNumber: SeverityNumber.ERROR,
              severityText: 'ERROR',
              body: 'MCP tool call failed',
              attributes: {
                  tool: name,
                  error: error.message
              }
          });
      }
      throw error;
    }
  }

  isConnected() {
    return !!this.client;
  }
}

export const mcpClient = new MCPClientService();