import {useEffect, useState} from 'react';
import {
  ApiError, display, fieldNames, fieldsFor, objectFields, organizationTypes,
  readRecord, request, text, valueAt, writeXml, type Entity, type XmlRecord
} from './api';
import EntityForm from './EntityForm';
import QueryForm, {type Query} from './QueryForm';

function ErrorMessage({error}: { error: unknown }) {
  if (!error) return null;
  return <div className="error" role="alert">
    <p>{error instanceof Error ? error.message : 'Не удалось выполнить операцию'}</p>
    {error instanceof ApiError && error.details.map(detail => <p key={detail}>{detail}</p>)}
  </div>;
}

function RecordTable({records, entity, busy, onEdit, onDelete}: {
  records: XmlRecord[];
  entity: Entity;
  busy: boolean;
  onEdit?: (record: XmlRecord) => void;
  onDelete?: (record: XmlRecord) => void;
}) {
  const fields = fieldsFor(entity).filter(field => !objectFields.includes(field));
  return <div className="table-wrap">
    <table>
      <thead>
      <tr>
        {fields.map(field => <th
          key={field}>{entity === 'employees' && field === 'name' ? 'Имя' : fieldNames[field]}</th>)}
        {onEdit && <th>Действия</th>}
      </tr>
      </thead>
      <tbody>
      {records.length === 0 && <tr>
          <td colSpan={fields.length + (onEdit ? 1 : 0)}>Записей нет</td>
      </tr>}
      {records.map(record => <tr key={valueAt(record, 'id')}>
        {fields.map(field => <td key={field}>{display(record, field)}</td>)}
        {onEdit && <td className="table-actions">
            <button disabled={busy} onClick={() => onEdit(record)}>Изменить</button>
            <button disabled={busy} onClick={() => onDelete?.(record)}>Удалить</button>
        </td>}
      </tr>)}
      </tbody>
    </table>
  </div>;
}

function Collection({entity}: { entity: Entity }) {
  const [records, setRecords] = useState<XmlRecord[]>([]);
  const [total, setTotal] = useState('0');
  const [query, setQuery] = useState<Query>({page: '1', size: '20', sort: 'id', filter: ''});
  const [loadedQuery, setLoadedQuery] = useState<Query | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [showForm, setShowForm] = useState(false);
  const [editing, setEditing] = useState<XmlRecord | null>(null);
  const [lookupId, setLookupId] = useState('');
  const [found, setFound] = useState<XmlRecord | null>(null);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setMessage('');
    try {
      await action();
    } catch (error) {
      setError(error);
    } finally {
      setBusy(false);
    }
  }

  async function load(next = query) {
    const params = new URLSearchParams({page: next.page, size: next.size, sort: next.sort});
    if (next.filter) params.set('filter', next.filter);
    const {root, headers} = await request(`/${entity}?${params}`);
    setRecords(Array.from(root!.children, readRecord));
    setTotal(headers.get('X-Total-Count') || '0');
    setLoadedQuery({
      ...next,
      page: headers.get('X-Page') || next.page,
      size: headers.get('X-Page-Size') || next.size
    });
  }

  useEffect(() => {
    run(() => load());
  }, []);

  const pages = loadedQuery ? (BigInt(total) + BigInt(loadedQuery.size) - 1n) / BigInt(loadedQuery.size) : 0n;

  function applyQuery(next: Query) {
    setQuery(next);
    run(() => load(next));
  }

  function changePage(offset: bigint) {
    if (loadedQuery) applyQuery({...loadedQuery, page: String(BigInt(loadedQuery.page) + offset)});
  }

  function edit(record: XmlRecord) {
    run(async () => {
      const {root} = await request(`/${entity}/${encodeURIComponent(valueAt(record, 'id'))}`);
      setEditing(readRecord(root!));
      setShowForm(true);
    });
  }

  async function save(data: XmlRecord) {
    await run(async () => {
      const path = editing ? `/${entity}/${encodeURIComponent(valueAt(editing, 'id'))}` : `/${entity}`;
      const body = writeXml(entity === 'organizations' ? 'organization' : 'employee', data);
      const {root} = await request(path, editing ? 'PUT' : 'POST', body);
      setFound(readRecord(root!));
      setShowForm(false);
      setMessage('Запись сохранена');
      await load();
    });
  }

  function remove(record: XmlRecord) {
    if (!window.confirm(`Удалить запись ${valueAt(record, 'name')} с ID ${valueAt(record, 'id')}`)) return;
    run(async () => {
      await request(`/${entity}/${encodeURIComponent(valueAt(record, 'id'))}`, 'DELETE');
      setFound(null);
      setMessage('Запись удалена');
      await load();
    });
  }

  function find() {
    setFound(null);
    run(async () => {
      const {root} = await request(`/${entity}/${encodeURIComponent(lookupId)}`);
      setFound(readRecord(root!));
    });
  }

  return <>
    <section>
      <h2>{entity === 'organizations' ? 'Организации' : 'Сотрудники'}</h2>
      <QueryForm entity={entity} query={query} busy={busy} onApply={applyQuery}/>
    </section>
    <ErrorMessage error={error}/>
    {message && <p className="success">{message}</p>}
    {showForm && <EntityForm key={editing ? valueAt(editing, 'id') : 'new'} entity={entity}
                             initial={editing} busy={busy} onSave={save} onCancel={() => setShowForm(false)}/>}
    <section>
      <div className="actions">
        <button disabled={busy} onClick={() => {
          setEditing(null);
          setShowForm(true);
        }}>
          Добавить {entity === 'organizations' ? 'организацию' : 'сотрудника'}
        </button>
        <button disabled={busy} onClick={() => run(() => load())}>Обновить список</button>
      </div>
      {busy && <p>Загрузка</p>}
      {loadedQuery && <>
          <p>Всего записей {total} страница {loadedQuery.page} из {String(pages || 1n)}</p>
          <RecordTable records={records} entity={entity} busy={busy} onEdit={edit} onDelete={remove}/>
          <div className="actions pagination">
              <button disabled={busy || BigInt(loadedQuery.page) <= 1n} onClick={() => changePage(-1n)}>Назад</button>
              <button disabled={busy || BigInt(loadedQuery.page) >= pages} onClick={() => changePage(1n)}>Вперед
              </button>
          </div>
      </>}
    </section>
    <section>
      <h2>Получить по ID</h2>
      <form className="query-row" onSubmit={event => {
        event.preventDefault();
        find();
      }}>
        <label>ID<input required value={lookupId} onChange={event => setLookupId(event.target.value)}/></label>
        <button disabled={busy}>Найти</button>
      </form>
      {found && <RecordTable records={[found]} entity={entity} busy={busy} onEdit={edit} onDelete={remove}/>}
    </section>
  </>;
}

function Operations() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState<XmlRecord | null>(null);
  const [threshold, setThreshold] = useState('PUBLIC');
  const [deleteType, setDeleteType] = useState('PUBLIC');
  const [organizationId, setOrganizationId] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [acquirerId, setAcquirerId] = useState('');
  const [acquiredId, setAcquiredId] = useState('');

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError(null);
    setMessage('');
    setResult(null);
    try {
      await action();
    } catch (error) {
      setError(error);
    } finally {
      setBusy(false);
    }
  }

  function count() {
    run(async () => {
      const {root} = await request(`/organizations/count?${new URLSearchParams({typeGreaterThan: threshold})}`);
      setMessage(`Найдено организаций ${text(root, 'count')}`);
    });
  }

  function removeByType() {
    if (!window.confirm(`Удалить одну организацию с типом ${deleteType}`)) return;
    run(async () => {
      await request(`/organizations/type/${deleteType}`, 'DELETE');
      setMessage('Одна организация удалена');
    });
  }

  function hire() {
    run(async () => {
      const {root} = await request(`/orgmanager/hire/${encodeURIComponent(organizationId)}?${new URLSearchParams({employeeId})}`, 'POST', undefined, true);
      setMessage(`Сотрудник принят в организацию ${text(root, 'organizationId')} всего сотрудников ${text(root, 'employeeCount')}`);
    });
  }

  function acquire() {
    if (!window.confirm(`Перенести сотрудников из ${acquiredId} в ${acquirerId} и удалить организацию ${acquiredId}`)) return;
    run(async () => {
      const {root} = await request(`/orgmanager/acquise/${encodeURIComponent(acquirerId)}/${encodeURIComponent(acquiredId)}`, 'POST', undefined, true);
      const organization = root!.querySelector('organization');
      if (organization) setResult(readRecord(organization));
      setMessage(`Организация ${text(root, 'acquiredId')} поглощена всего сотрудников ${text(root, 'employeeCount')}`);
    });
  }

  const typeOptions = organizationTypes.map(type => <option key={type}>{type}</option>);
  return <>
    <h2>Дополнительные операции и менеджер</h2>
    <ErrorMessage error={error}/>
    {message && <p className="success">{message}</p>}
    {result && <RecordTable entity="organizations" records={[result]} busy={busy}/>}
    <section>
      <h3>Подсчет организаций</h3>
      <p>Порядок типов от меньшего к большему</p>
      <p>PUBLIC, GOVERNMENT, TRUST, PRIVATE_LIMITED_COMPANY</p>
      <form className="query-row" onSubmit={event => {
        event.preventDefault();
        count();
      }}>
        <label>Тип для сравнения<select disabled={busy} value={threshold}
                                        onChange={event => setThreshold(event.target.value)}>{typeOptions}</select></label>
        <button disabled={busy}>Посчитать</button>
      </form>
    </section>
    <section>
      <h3>Удаление одной организации по типу</h3>
      <label>Тип<select disabled={busy} value={deleteType}
                        onChange={event => setDeleteType(event.target.value)}>{typeOptions}</select></label>
      <button disabled={busy} onClick={removeByType}>Удалить по типу</button>
    </section>
    <section>
      <h3>Найм сотрудника</h3>
      <form className="query-row" onSubmit={event => {
        event.preventDefault();
        hire();
      }}>
        <label>ID организации<input required value={organizationId} disabled={busy}
                                    onChange={event => setOrganizationId(event.target.value)}/></label>
        <label>ID сотрудника<input required value={employeeId} disabled={busy}
                                   onChange={event => setEmployeeId(event.target.value)}/></label>
        <button disabled={busy}>Нанять</button>
      </form>
    </section>
    <section>
      <h3>Поглощение организации</h3>
      <p>Сотрудники перейдут в первую организацию а вторая будет удалена</p>
      <p>При ошибке проверьте данные и укажите те же ID для продолжения</p>
      <form className="query-row" onSubmit={event => {
        event.preventDefault();
        acquire();
      }}>
        <label>ID первой организации<input required disabled={busy} value={acquirerId}
                                           onChange={event => setAcquirerId(event.target.value)}/></label>
        <label>ID второй организации<input required disabled={busy} value={acquiredId}
                                           onChange={event => setAcquiredId(event.target.value)}/></label>
        <button disabled={busy}>Выполнить поглощение</button>
      </form>
    </section>
    {busy && <p>Загрузка</p>}
  </>;
}

export default function App() {
  const [tab, setTab] = useState<Entity | 'operations'>('organizations');
  return <main>
    <h1>Организации и сотрудники</h1>
    <nav>
      <button aria-current={tab === 'organizations' ? 'page' : undefined}
              onClick={() => setTab('organizations')}>Организации
      </button>
      <button aria-current={tab === 'employees' ? 'page' : undefined} onClick={() => setTab('employees')}>Сотрудники
      </button>
      <button aria-current={tab === 'operations' ? 'page' : undefined} onClick={() => setTab('operations')}>Операции
      </button>
    </nav>
    {tab === 'operations' ? <Operations/> : <Collection key={tab} entity={tab}/>}
  </main>;
}
