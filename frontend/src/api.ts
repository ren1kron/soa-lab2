import {ApiError, serviceError, uncertainResult} from './errors';

export {ApiError} from './errors';

export type XmlRecord = { [key: string]: string | XmlRecord };
export type Entity = 'organizations' | 'employees';
type Result = { root: Element | null; headers: Headers };

export const text = (node: Element | null, field: string): string =>
  node?.querySelector(field)?.textContent ?? '';

function parseXml(source: string): Element {
  const doc = new DOMParser().parseFromString(source, 'application/xml');
  if (doc.querySelector('parsererror')) throw new Error('Сервис вернул неверный XML');
  return doc.documentElement;
}

export function readRecord(node: Element): XmlRecord {
  const record: XmlRecord = {};
  for (const child of node.children) {
    record[child.tagName] = child.children.length ? readRecord(child) : child.textContent ?? '';
  }
  return record;
}

const escapeXml = (value: string) => value.replace(/[<>&"']/g, char => ({
  '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;', "'": '&apos;',
})[char]!);

export function writeXml(root: string, data: XmlRecord): string {
  let xml = `<${root}>`;
  for (const [key, value] of Object.entries(data)) {
    if (typeof value === 'string') xml += `<${key}>${escapeXml(value)}</${key}>`;
    else xml += writeXml(key, value);
  }
  return xml + `</${root}>`;
}

export async function request(path: string, method = 'GET', body?: string, manager = false): Promise<Result> {
  const mutation = method !== 'GET';
  let response: Response;
  try {
    response = await fetch(`/api/${manager ? 'manager' : 'organizations'}${path}`, {
      method,
      headers: {Accept: 'application/xml', ...(body === undefined ? {} : {'Content-Type': 'application/xml'})},
      body,
      signal: AbortSignal.timeout(60000),
    });
  } catch {
    throw new ApiError('Нет связи с сервисом', mutation ? uncertainResult : []);
  }
  let source: string;
  try {
    source = await response.text();
  } catch {
    throw new ApiError('Ответ сервиса прерван', mutation ? uncertainResult : []);
  }
  let root: Element | null = null;
  try {
    root = source ? parseXml(source) : null;
  } catch {
    if (response.ok) throw new ApiError('Сервис вернул неверный ответ', mutation ? uncertainResult : []);
  }
  if (!response.ok) throw serviceError(root, response.status, path, method, fieldNames);
  if (method !== 'DELETE' && !root) throw new ApiError('Сервис вернул пустой ответ', mutation ? uncertainResult : []);
  return {root, headers: response.headers};
}

export const organizationTypes = ['PUBLIC', 'GOVERNMENT', 'TRUST', 'PRIVATE_LIMITED_COMPANY'];

export const valueAt = (record: XmlRecord, path: string): string => {
  let value: string | XmlRecord = record;
  for (const part of path.split('.')) {
    if (typeof value === 'string') return '';
    value = value[part] ?? '';
  }
  return typeof value === 'string' ? value : '';
};
export const fieldNames: Record<string, string> = {
  id: 'ID',
  name: 'Название',
  'coordinates.x': 'Координата X',
  'coordinates.y': 'Координата Y',
  creationDate: 'Дата создания',
  annualTurnover: 'Годовой оборот',
  type: 'Тип',
  officialAddress: 'Адрес',
  'officialAddress.zipCode': 'Индекс',
  'officialAddress.town': 'Город',
  'officialAddress.town.name': 'Название города',
  'officialAddress.town.x': 'X города',
  'officialAddress.town.y': 'Y города',
  organizationId: 'ID организации',
};
export const fieldsFor = (entity: Entity) => entity === 'employees'
  ? ['id', 'name', 'organizationId'] : Object.keys(fieldNames).filter(field => field !== 'organizationId');
export const objectFields = ['officialAddress', 'officialAddress.town'];

export function display(record: XmlRecord, field: string): string {
  const value = valueAt(record, field);
  if (!value) return 'Нет';
  if (field === 'creationDate') return value.split('-').reverse().join('-');
  return value;
}

export function filterValueForApi(field: string, value: string): string {
  if (field !== 'creationDate') return value;
  return value.split('|').map(date => date.replace(/^(\d{2})-(\d{2})-(\d{4})$/, '$3-$2-$1')).join('|');
}
