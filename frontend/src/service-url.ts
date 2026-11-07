type Origins = { organizations?: string; manager?: string };

export function serviceUrl(path: string, manager: boolean, origins: Origins = {}): string {
  const configured = manager ? origins.manager : origins.organizations;
  if (!configured) return `/api/${manager ? 'manager' : 'organizations'}${path}`;
  const url = new URL(configured);
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('API URL must be an HTTPS origin');
  }
  return url.origin + path;
}
