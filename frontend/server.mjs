import { createServer, request, Agent } from 'node:https';
import { readFileSync, createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';

const dist = resolve(import.meta.dirname, 'dist');
const routes = [
    ['/api/organizations', process.env.ORGANIZATIONS_URL || 'https://localhost:26125', process.env.ORGANIZATIONS_CA || '../.local/certs/organizations.crt'],
    ['/api/manager', process.env.MANAGER_URL || 'https://localhost:26126', process.env.MANAGER_CA || '../.local/certs/manager.crt'],
].map(([prefix, target, ca]) => {
    const url = new URL(target);
    if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
        throw new Error('API URL must be an HTTPS origin');
    }
    return { prefix, target, agent: new Agent({ ca: readFileSync(resolve(ca)) }) };
});
const tls = {
    cert: readFileSync(process.env.FRONTEND_CERT || '../.local/certs/frontend.crt'),
    key: readFileSync(process.env.FRONTEND_KEY || '../.local/certs/frontend.key'),
};
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.ico': 'image/x-icon' };
const server = createServer(tls, async (req, res) => {
    const rawPath = req.url || '/';
    const route = routes.find(item => rawPath.startsWith(item.prefix + '/'));
    if (route) {
        const headers = { ...req.headers, host: new URL(route.target).host };
        delete headers.connection;
        const upstream = request(new URL(route.target), {
            method: req.method, path: rawPath.slice(route.prefix.length), headers, agent: route.agent,
        }, response => {
            res.writeHead(response.statusCode || 502, response.headers);
            response.pipe(res);
            response.on('error', () => res.destroy());
        });
        upstream.setTimeout(55000, () => upstream.destroy(new Error('Upstream timeout')));
        upstream.on('error', () => {
            if (res.headersSent) { res.destroy(); return; }
            res.writeHead(502, { 'Content-Type': 'application/xml; charset=utf-8' });
            res.end('<error><code>PROXY_ERROR</code><message>Нет ответа сервиса проверьте данные перед повтором</message><details/></error>');
        });
        req.on('aborted', () => upstream.destroy());
        req.pipe(upstream);
        return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405); res.end(); return; }
    try {
        const pathname = decodeURIComponent(new URL(rawPath, 'https://localhost').pathname);
        const file = resolve(dist, '.' + (pathname === '/' ? '/index.html' : pathname));
        if (!file.startsWith(dist + sep)) { res.writeHead(403); res.end(); return; }
        if (!(await stat(file)).isFile()) throw new Error('Not a file');
        res.writeHead(200, { 'Content-Type': mime[extname(file)] || 'application/octet-stream' });
        if (req.method === 'HEAD') res.end();
        else createReadStream(file).on('error', () => res.destroy()).pipe(res);
    } catch { res.writeHead(404); res.end('Not found'); }
});
server.listen(Number(process.env.PORT || 26127), process.env.HOST || '127.0.0.1', () => {
    console.log(`Frontend: https://${process.env.HOST || '127.0.0.1'}:${process.env.PORT || 26127}`);
});
