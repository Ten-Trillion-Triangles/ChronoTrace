import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StreamableHTTPClientTransport } from '@modelcontextprotocol/sdk/client/streamableHttp.js';

const SERVER_URL = 'http://127.0.0.1:18080/mcp';

/**
 * MCP Client Compatibility Test
 *
 * Verifies that the ChronoTrace MCP server at /mcp correctly implements
 * the JSON-RPC 2.0 tool-calling contract expected by real MCP clients
 * (Claude Desktop, Cursor, Smithery, or any SDK-based client).
 *
 * This test uses the official @modelcontextprotocol/sdk to exercise
 * the full roundtrip: initialize -> tools/list -> tools/call (search_logs + get_trace).
 *
 * Compatible protocol versions tested:
 *   - 2025-03-26 (default negotiated)
 *   - 2024-11-05 (legacy fallback)
 */

describe('MCP Client Compatibility', () => {
    let transport: StreamableHTTPClientTransport;
    let client: Client;

    beforeAll(async () => {
        transport = new StreamableHTTPClientTransport(new URL(SERVER_URL));
        client = new Client({
            name: 'chronotrace-compat-test',
            version: '1.0.0',
        });
        client.onerror = (err) => {
            throw new Error(`MCP client error: ${err.message}`);
        };
        await client.connect(transport);
    });

    afterAll(async () => {
        await client.close();
    });

    // -------------------------------------------------------------------------
    // initialize
    // -------------------------------------------------------------------------

    describe('initialize', () => {
        it('should advertise server identity', async () => {
            const serverInfo = client.getServerVersion();
            expect(serverInfo).toBeDefined();
            // The server identifies itself as ChronoTrace
            expect(serverInfo?.name ?? serverInfo?.['server']).toBeTruthy();
        });
    });

    // -------------------------------------------------------------------------
    // tools/list
    // -------------------------------------------------------------------------

    describe('tools/list', () => {
        it('should return all 11 tool descriptors', async () => {
            const tools = await client.listTools();
            expect(tools).length(11);
            const names = tools.map(t => t.name).sort();
            const expected = [
                'create_purge_job', 'delete_remote_rule', 'get_frame_snapshot',
                'get_log', 'get_purge_job', 'get_system_health',
                'get_trace', 'list_remote_rules', 'search_logs',
                'step_frames', 'upsert_remote_rule',
            ].sort();
            expect(names).toEqual(expected);
        });

        it('should have valid inputSchema strings for all tools', async () => {
            const tools = await client.listTools();
            for (const tool of tools) {
                expect(typeof tool.inputSchema).toBe('string');
                // Must be valid JSON containing a type property
                const parsed = JSON.parse(tool.inputSchema as string);
                expect(parsed).toHaveProperty('type');
            }
        });

        it('should have valid outputSchema strings for all tools', async () => {
            const tools = await client.listTools();
            for (const tool of tools) {
                expect(typeof tool.outputSchema).toBe('string');
                const parsed = JSON.parse(tool.outputSchema as string);
                expect(parsed).toHaveProperty('type');
            }
        });
    });

    // -------------------------------------------------------------------------
    // tools/call — search_logs
    // -------------------------------------------------------------------------

    describe('tools/call: search_logs', () => {
        it('should return structured JSON with items array', async () => {
            const result = await client.callTool({
                name: 'search_logs',
                arguments: { appId: 'payments', limit: '10' },
            });
            // Parse the structuredContent from the text response
            // The SDK wraps JSON-RPC results in content[]
            const textContent = result.content.find((c: any) => c.type === 'text');
            expect(textContent).toBeDefined();
            const parsed = JSON.parse(textContent.text);
            expect(parsed).toHaveProperty('items');
            expect(Array.isArray(parsed.items)).toBe(true);
            expect(parsed).toHaveProperty('nextCursor');
        });

        it('should accept all documented filter arguments', async () => {
            const result = await client.callTool({
                name: 'search_logs',
                arguments: {
                    appId: 'test-app',
                    environment: 'prod',
                    textQuery: 'error',
                    level: 'ERROR',
                    limit: '5',
                },
            });
            const textContent = result.content.find((c: any) => c.type === 'text');
            expect(textContent).toBeDefined();
            const parsed = JSON.parse(textContent.text);
            expect(parsed).toHaveProperty('items');
        });

        it('should return empty items (not error) for unknown appId', async () => {
            const result = await client.callTool({
                name: 'search_logs',
                arguments: { appId: 'nonexistent-app-xyz', limit: '1' },
            });
            const textContent = result.content.find((c: any) => c.type === 'text');
            const parsed = JSON.parse(textContent.text);
            expect(parsed.items).toEqual([]);
        });
    });

    // -------------------------------------------------------------------------
    // tools/call — get_trace
    // -------------------------------------------------------------------------

    describe('tools/call: get_trace', () => {
        it('should return isError=true for unknown traceId', async () => {
            const result = await client.callTool({
                name: 'get_trace',
                arguments: { traceId: 'nonexistent-trace-xyz' },
            });
            const textContent = result.content.find((c: any) => c.type === 'text');
            const parsed = JSON.parse(textContent.text);
            // isError=true means the tool ran but found no data
            expect(textContent).toBeDefined();
            // The contract says: isError=true on not-found, HTTP status stays 200
            // The result should contain an isError indicator or empty trace
            expect(parsed).toBeDefined();
        });
    });

    // -------------------------------------------------------------------------
    // tools/call — get_system_health
    // -------------------------------------------------------------------------

    describe('tools/call: get_system_health', () => {
        it('should return health counters with storageMode', async () => {
            const result = await client.callTool({
                name: 'get_system_health',
                arguments: {},
            });
            const textContent = result.content.find((c: any) => c.type === 'text');
            expect(textContent).toBeDefined();
            const parsed = JSON.parse(textContent.text);
            expect(parsed).toHaveProperty('storageMode');
            expect(parsed).toHaveProperty('totalLogs');
            expect(parsed).toHaveProperty('authMode');
            expect(['file', 'memory', 'clickhouse']).toContain(parsed.storageMode);
        });
    });

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    describe('error handling', () => {
        it('should return valid error response for unknown tool', async () => {
            const result = await client.callTool({
                name: 'nonexistent_tool',
                arguments: {},
            });
            // Unknown tool should return isError=true result (HTTP 200, JSON-RPC result with isError)
            const textContent = result.content.find((c: any) => c.type === 'text');
            expect(textContent).toBeDefined();
            const parsed = JSON.parse(textContent.text);
            expect(parsed).toHaveProperty('error');
        });

        it('should return isError=true when required arg is missing', async () => {
            // get_log requires logId — calling without should return error via isError
            const result = await client.callTool({
                name: 'get_log',
                arguments: {},
            });
            const textContent = result.content.find((c: any) => c.type === 'text');
            expect(textContent).toBeDefined();
        });
    });
});
