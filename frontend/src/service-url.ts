type Origins = {organizations?: string; manager?: string; helios?: boolean};

export function serviceUrl(path: string, manager: boolean, origins: Origins = {}): string {
  const configured = manager ? origins.manager : origins.organizations;
  if (origins.helios || !configured) {
    const gateway = origins.helios ? './api.php' : '/api';
    return `${gateway}/${manager ? 'manager' : 'organizations'}${path}`;
  }
  const url = new URL(configured);
  if (url.protocol !== 'https:' || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error('API URL must be an HTTPS origin');
  }
  return url.origin + path;
}
