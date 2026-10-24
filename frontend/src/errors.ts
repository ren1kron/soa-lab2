export class ApiError extends Error {
  constructor(message: string, public details: string[] = []) {
    super(message);
  }
}

const messages: Record<string, string> = {
  NOT_FOUND: 'Организация или сотрудник не найдены',
  ORGANIZATION_NOT_EMPTY: 'Нельзя удалить организацию пока в ней есть сотрудники',
  EMPLOYEE_ALREADY_ASSIGNED: 'Сотрудник уже работает в другой организации',
  SELF_ACQUISITION: 'Выберите две разные организации',
  CONCURRENT_MODIFICATION: 'Данные уже изменились обновите страницу',
  INVALID_REQUEST: 'Проверьте введенные данные',
  VALIDATION_FAILED: 'Проверьте введенные данные',
  VALIDATION_ERROR: 'Проверьте введенные данные',
  INVALID_SORT: 'Проверьте сортировку',
  INVALID_FILTER: 'Проверьте фильтры',
  MALFORMED_XML: 'Не удалось отправить данные',
  MISSING_BODY: 'Не переданы данные формы',
  METHOD_NOT_ALLOWED: 'Эта операция недоступна',
  NOT_ACCEPTABLE: 'Не удалось получить данные',
  UNSUPPORTED_MEDIA_TYPE: 'Не удалось отправить данные',
  INTERNAL_ERROR: 'Ошибка сервиса попробуйте позже',
  UPSTREAM_INTERNAL_ERROR: 'Ошибка сервиса организаций попробуйте позже',
  INTEGRATION_FAILURE: 'Не удалось выполнить операцию',
  INVALID_UPSTREAM_RESPONSE: 'Сервис организаций вернул неверный ответ',
  DEPENDENCY_UNAVAILABLE: 'Нет связи с сервисом организаций',
  SERVICE_UNAVAILABLE: 'Сервис временно недоступен',
  UPSTREAM_TIMEOUT: 'Сервис организаций не ответил вовремя',
  PROXY_ERROR: 'Нет связи с сервисом',
};

const statuses: Record<number, string> = {
  400: 'Проверьте введенные данные',
  404: messages.NOT_FOUND,
  405: messages.METHOD_NOT_ALLOWED,
  406: messages.NOT_ACCEPTABLE,
  409: 'Не удалось выполнить операцию обновите данные',
  415: messages.UNSUPPORTED_MEDIA_TYPE,
  422: 'Проверьте введенные данные',
  500: messages.INTERNAL_ERROR,
  502: 'Не удалось получить ответ сервиса',
  503: messages.SERVICE_UNAVAILABLE,
  504: 'Сервис не ответил вовремя',
};

const reasons: Record<string, string> = {
  REQUIRED: 'Заполните поле',
  TOO_SHORT: 'Введите хотя бы один символ',
  TOO_LONG: 'Значение слишком длинное',
  OUT_OF_RANGE: 'Значение выходит за допустимые границы',
  INVALID_TYPE: 'Неверное значение',
  INVALID_FORMAT: 'Неверный формат',
  INVALID_ENUM: 'Выберите тип организации',
  INVALID_OPERATOR: 'Выберите другое условие фильтра',
  UNKNOWN_FIELD: 'Это поле недоступно',
  READ_ONLY_FIELD: 'Это поле нельзя изменить',
  DUPLICATE_FIELD: 'Поле передано несколько раз',
  DUPLICATE_PARAMETER: 'Параметр передан несколько раз',
  DUPLICATE_SORT: 'Уберите повтор поля сортировки',
  CONFLICTING_SORT: 'Уберите повтор поля сортировки',
  MISSING_BODY: 'Заполните форму',
  UNEXPECTED_BODY: 'Не удалось отправить запрос',
  UNSUPPORTED_MEDIA_TYPE: 'Не удалось отправить данные',
};

export const uncertainResult = ['Неизвестно сохранились ли изменения', 'Проверьте данные перед повтором'];

export function serviceError(root: Element | null, status: number, path: string, method: string, labels: Record<string, string>): ApiError {
  const value = (node: Element | null, field: string) => node?.querySelector(field)?.textContent ?? '';
  const code = value(root, 'code');
  let message = messages[code] || statuses[status] || 'Не удалось выполнить запрос';
  if (code === 'NOT_FOUND' || status === 404) {
    if (/^\/organizations(?:\/|\?|$)/.test(path)) message = 'Организация не найдена';
    if (/^\/employees\//.test(path) && ['GET', 'DELETE'].includes(method)) message = 'Сотрудник не найден';
  }
  const fields: Record<string, string> = {
    ...labels,
    name: path.startsWith('/employees') ? 'Имя' : 'Название',
    $: 'Данные формы', page: 'Страница', size: 'Записей на странице',
    employeeId: 'ID сотрудника', acquirerId: 'ID первой организации', acquiredId: 'ID второй организации',
    typeGreaterThan: 'Тип для сравнения', sort: 'Сортировка', filter: 'Фильтры',
  };
  const details = Array.from(root?.querySelectorAll('details > detail') ?? [], node => {
    const field = value(node, 'field');
    const reason = value(node, 'code');
    const indexed = /^(filter|sort)\[(\d+)\]$/.exec(field);
    const label = fields[field] || (indexed ? `${indexed[1] === 'filter' ? 'Фильтр' : 'Сортировка'} ${BigInt(indexed[2]) + 1n}` : 'Поле формы');
    let hint = reasons[reason] || 'Проверьте значение';
    if (['INVALID_TYPE', 'INVALID_FORMAT'].includes(reason)) {
      if (field === 'creationDate') hint = 'Введите существующую дату в формате ДД-ММ-ГГГГ';
      else if (['id', 'organizationId', 'employeeId', 'acquirerId', 'acquiredId', 'page'].includes(field)) hint = 'Введите целое число больше нуля';
      else if (field.startsWith('coordinates.') || /\.town\.[xy]$/.test(field)) hint = 'Введите целое число';
    }
    if (reason === 'OUT_OF_RANGE') {
      if (field === 'size') hint = 'Введите число от 1 до 100';
      else if (field === 'page') hint = 'Введите целое число больше нуля';
      else if (field === 'annualTurnover') hint = 'Введите число больше нуля';
      else if (field === 'coordinates.y') hint = 'Введите число от -2147483648 до 2147483647';
      else if (field.startsWith('coordinates.') || /\.town\.[xy]$/.test(field)) hint = 'Введите число от -9223372036854775808 до 9223372036854775807';
      else if (['id', 'organizationId', 'employeeId', 'acquirerId', 'acquiredId'].includes(field)) hint = 'Введите число от 1 до 9223372036854775807';
    }
    if (reason === 'TOO_LONG' && field === 'officialAddress.zipCode') hint = 'Введите не больше 18 символов';
    return `${label}: ${hint}`;
  });
  if (method !== 'GET' && status >= 500) details.push(...uncertainResult);
  return new ApiError(message, [...new Set(details)]);
}
