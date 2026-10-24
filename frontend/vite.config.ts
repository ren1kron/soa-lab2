import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { Agent } from 'node:https';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode, command }) => {
    const env = { ...loadEnv(mode, process.cwd(), ''), ...process.env };
    const proxy = (target: string, cert: string) => {
        if (!target.startsWith('https://')) throw new Error('API URL must use HTTPS');
        return {
            target,
            changeOrigin: true,
            agent: new Agent({ ca: readFileSync(resolve(cert)) }),
            rewrite: (path: string) => path.replace(/^\/api\/(organizations|manager)/, ''),
        };
    };
    return {
        plugins: [react()],
        server: command === 'serve' ? {
            port: 5173,
            strictPort: true,
            proxy: {
                '/api/organizations': proxy(env.ORGANIZATIONS_URL || 'https://localhost:26125', env.ORGANIZATIONS_CA || '../.local/certs/organizations.crt'),
                '/api/manager': proxy(env.MANAGER_URL || 'https://localhost:26126', env.MANAGER_CA || '../.local/certs/manager.crt'),
            },
        } : undefined,
    };
});
