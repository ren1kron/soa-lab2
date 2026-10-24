import {useEffect, useState} from 'react';
import {type Entity, fieldsFor, fieldNames, objectFields, filterValueForApi} from './api';

export type Query = { page: string; size: string; sort: string; filter: string };
type Filter = { field: string; operator: string; value: string };
type Sort = { field: string; descending: boolean };

const operators: Record<string, string> = {
  eq: '=', ne: '≠', gt: '>', gte: '≥', lt: '<', lte: '≤',
  contains: 'Содержит', startsWith: 'Начинается с', in: 'В списке', isNull: 'Не задано'
};
const datePattern = '[0-9]{2}-[0-9]{2}-[0-9]{4}';

function allowedOperators(field: string) {
  if (objectFields.includes(field)) return ['isNull'];
  let result = ['eq', 'ne', 'gt', 'gte', 'lt', 'lte', 'in'];
  if (['name', 'officialAddress.zipCode', 'officialAddress.town.name'].includes(field)) {
    result = ['eq', 'ne', 'contains', 'startsWith', 'in'];
  }
  if (['annualTurnover', 'type', 'organizationId'].includes(field) || field.startsWith('officialAddress')) {
    result.push('isNull');
  }
  return result;
}

export default function QueryForm({entity, query, busy, onApply}: {
  entity: Entity; query: Query; busy: boolean; onApply: (query: Query) => void;
}) {
  const fields = fieldsFor(entity);
  const sortFields = fields.filter(field => !objectFields.includes(field));
  const [filters, setFilters] = useState<Filter[]>([]);
  const [sorts, setSorts] = useState<Sort[]>([{field: 'id', descending: false}]);
  const [page, setPage] = useState(query.page);
  const [size, setSize] = useState(query.size);

  useEffect(() => {
    setPage(query.page);
    setSize(query.size);
  }, [query.page, query.size]);

  function updateSort(index: number, changes: Partial<Sort>) {
    setSorts(sorts.map((sort, i) => i === index ? {...sort, ...changes} : sort));
  }

  function updateFilter(index: number, changes: Partial<Filter>) {
    setFilters(filters.map((filter, i) => i === index ? {...filter, ...changes} : filter));
  }

  function changeFilterField(index: number, field: string) {
    const operator = allowedOperators(field)[0];
    updateFilter(index, {field, operator, value: operator === 'isNull' ? 'true' : ''});
  }

  function addSort() {
    const field = sortFields.find(field => !sorts.some(sort => sort.field === field));
    if (field) setSorts([...sorts, {field, descending: false}]);
  }

  function apply() {
    const sort = sorts.map(sort => (sort.descending ? '-' : '') + sort.field).join(',');
    const filter = filters.map(filter => {
      const value = filterValueForApi(filter.field, filter.value);
      return `${filter.field}:${filter.operator}:${value}`;
    }).join(',');
    onApply({page, size, sort, filter});
  }

  function reset() {
    setFilters([]);
    setSorts([{field: 'id', descending: false}]);
    setPage('1');
    setSize('20');
    onApply({page: '1', size: '20', sort: 'id', filter: ''});
  }

  return <form onSubmit={event => {
    event.preventDefault();
    apply();
  }}>
    <fieldset disabled={busy}>
      <h3>Сортировка</h3>
      {sorts.map((sort, index) => <div className="query-row" key={index}>
        <select aria-label={`Поле сортировки ${index + 1}`} value={sort.field}
                onChange={event => updateSort(index, {field: event.target.value})}>
          {sortFields.map(field => <option key={field} value={field}>{fieldNames[field]}</option>)}
        </select>
        <select aria-label={`Направление ${index + 1}`} value={sort.descending ? 'desc' : 'asc'}
                onChange={event => updateSort(index, {descending: event.target.value === 'desc'})}>
          <option value="asc">По возрастанию</option>
          <option value="desc">По убыванию</option>
        </select>
        <button type="button" disabled={sorts.length === 1}
                onClick={() => setSorts(sorts.filter((_, i) => i !== index))}>Убрать
        </button>
      </div>)}
      <button type="button" disabled={sorts.length === sortFields.length} onClick={addSort}>Добавить поле</button>
      <h3>Фильтры</h3>
      {filters.map((filter, index) => {
        const isDate = filter.field === 'creationDate';
        const isList = filter.operator === 'in';
        let placeholder = isList ? 'Значения через |' : 'Значение';
        let pattern: string | undefined;
        if (isDate) {
          placeholder = isList ? 'ДД-ММ-ГГГГ|ДД-ММ-ГГГГ' : 'ДД-ММ-ГГГГ';
          pattern = isList ? `${datePattern}(\\|${datePattern})*` : datePattern;
        }
        return <div className="query-row" key={index}>
          <select aria-label={`Поле фильтра ${index + 1}`} value={filter.field}
                  onChange={event => changeFilterField(index, event.target.value)}>
            {fields.map(field => <option key={field} value={field}>{fieldNames[field]}</option>)}
          </select>
          <select aria-label={`Оператор ${index + 1}`} value={filter.operator} onChange={event => updateFilter(index, {
            operator: event.target.value,
            value: event.target.value === 'isNull' ? 'true' : ''
          })}>
            {allowedOperators(filter.field).map(operator => <option key={operator}
                                                                    value={operator}>{operators[operator]}</option>)}
          </select>
          {filter.operator === 'isNull' ?
            <select aria-label={`Значение ${index + 1}`} value={filter.value}
                    onChange={event => updateFilter(index, {value: event.target.value})}>
              <option value="true">Да</option>
              <option value="false">Нет</option>
            </select> :
            <input aria-label={`Значение ${index + 1}`} value={filter.value} placeholder={placeholder} pattern={pattern}
                   title={isDate ? 'Введите дату в формате ДД-ММ-ГГГГ' : undefined}
                   onChange={event => updateFilter(index, {value: event.target.value})}/>
          }
          <button type="button" onClick={() => setFilters(filters.filter((_, i) => i !== index))}>Убрать</button>
        </div>;
      })}
      <button type="button"
              onClick={() => setFilters([...filters, {field: 'name', operator: 'contains', value: ''}])}>Добавить фильтр
      </button>
      <div className="query-row">
        <label>Страница<input value={page} required pattern="[0-9]+"
                              onChange={event => setPage(event.target.value)}/></label>
        <label>Записей на странице<input type="number" min="1" max="100" required value={size}
                                         onChange={event => setSize(event.target.value)}/></label>
        <button type="submit">Применить</button>
        <button type="button" onClick={reset}>Сбросить</button>
      </div>
    </fieldset>
  </form>;
}
