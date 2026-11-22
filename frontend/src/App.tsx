import {useEffect, useId, useRef, useState} from 'react';
import {
  ApiError, display, organizationTypes, recordFields, recordLabel,
  readRecord, request, text, valueAt, writeXml, type Entity, type XmlRecord
} from './api';
import Icon from './Icon';
import RecordTable from './RecordTable';
import EntityForm from './EntityForm';
import QueryForm, {type Query} from './QueryForm';

function ErrorMessage({error}: { error: unknown }) {
  if (!error) return null;
  return <div className="error" role="alert" tabIndex={-1}>
    <p>{error instanceof Error ? error.message : 'Не удалось выполнить операцию'}</p>
    {error instanceof ApiError && error.details.map(detail => <p key={detail}>{detail}</p>)}
  </div>;
}

type RecordView =
  | {mode: 'info'; record: XmlRecord}
  | {mode: 'edit'; record: XmlRecord; fromInfo: boolean};

function Collection({entity}: { entity: Entity }) {
  const [records, setRecords] = useState<XmlRecord[]>([]);
  const [total, setTotal] = useState('0');
  const [query, setQuery] = useState<Query>({page: '1', size: '20', sort: 'id', filter: ''});
  const [loadedQuery, setLoadedQuery] = useState<Query | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [message, setMessage] = useState('');
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [view, setView] = useState<RecordView | null>(null);
  const [lookupId, setLookupId] = useState('');
  const dialog = useRef<HTMLDialogElement>(null);
  const returnFocus = useRef<HTMLElement>(null);
  const addButton = useRef<HTMLButtonElement>(null);
  const lookupInput = useRef<HTMLInputElement>(null);
  const refreshButton = useRef<HTMLButtonElement>(null);
  const dialogHeadingId = useId();
  const [wideLayout, setWideLayout] = useState(() => window.matchMedia('(min-width: 961px)').matches);
  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 961px)');
    const adapt = () => setWideLayout(desktop.matches);
    desktop.addEventListener('change', adapt);
    return () => desktop.removeEventListener('change', adapt);
  }, []);

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

  useEffect(() => {
    const modal = dialog.current;
    if (view && modal) {
      if (!modal.open) modal.showModal();
      if (busy) return;
      const target = error ? '[role="alert"]' : view.mode === 'info' ? 'h2' : 'input[name="name"]';
      modal.querySelector<HTMLElement>(target)?.focus();
    } else if (!busy && returnFocus.current) {
      const trigger = returnFocus.current;
      returnFocus.current = null;
      (trigger.isConnected ? trigger : refreshButton.current)?.focus();
    }
  }, [view, busy, error]);

  const pages = loadedQuery ? (BigInt(total) + BigInt(loadedQuery.size) - 1n) / BigInt(loadedQuery.size) : 0n;

  function applyQuery(next: Query) {
    setQuery(next);
    run(() => load(next));
  }

  function changePage(offset: bigint) {
    if (loadedQuery) applyQuery({...loadedQuery, page: String(BigInt(loadedQuery.page) + offset)});
  }

  function info(record: XmlRecord, trigger: HTMLElement) {
    if (busy) return;
    returnFocus.current = trigger;
    setError(null);
    setView({mode: 'info', record});
  }

  function edit(record: XmlRecord, trigger?: HTMLButtonElement) {
    if (busy) return;
    if (trigger) returnFocus.current = trigger;
    run(async () => {
      const {root} = await request(`/${entity}/${encodeURIComponent(valueAt(record, 'id'))}`);
      setView({mode: 'edit', record: readRecord(root!), fromInfo: view?.mode === 'info'});
    });
  }

  function dismiss() {
    if (busy) return;
    setError(null);
    if (view?.mode === 'edit' && view.fromInfo) {
      setView({mode: 'info', record: view.record});
    } else {
      dialog.current?.close();
      setView(null);
    }
  }

  async function save(data: XmlRecord, record: XmlRecord | null) {
    await run(async () => {
      const path = record ? `/${entity}/${encodeURIComponent(valueAt(record, 'id'))}` : `/${entity}`;
      const body = writeXml(entity === 'organizations' ? 'organization' : 'employee', data);
      const {root} = await request(path, record ? 'PUT' : 'POST', body);
      if (!record) {
        returnFocus.current = addButton.current;
        setShowCreateForm(false);
      }
      setView({mode: 'info', record: readRecord(root!)});
      setMessage('Запись сохранена');
      await load(loadedQuery ?? query);
    });
  }

  function remove(record: XmlRecord, trigger?: HTMLButtonElement) {
    if (!window.confirm(`Удалить запись ${valueAt(record, 'name')} с ID ${valueAt(record, 'id')}`)) return;
    if (trigger) returnFocus.current = trigger;
    run(async () => {
      await request(`/${entity}/${encodeURIComponent(valueAt(record, 'id'))}`, 'DELETE');
      dialog.current?.close();
      setView(null);
      setMessage('Запись удалена');
      await load(loadedQuery ?? query);
    });
  }

  function find(trigger: HTMLElement | null) {
    returnFocus.current = trigger;
    run(async () => {
      const {root} = await request(`/${entity}/${encodeURIComponent(lookupId)}`);
      setView({mode: 'info', record: readRecord(root!)});
    });
  }

  const organization = entity === 'organizations';
  return <>
    <div className="page-heading">
      <h1>{organization ? 'Организации' : 'Сотрудники'}</h1>
      <button ref={addButton} className="primary" aria-label={organization ? 'Добавить организацию' : 'Добавить сотрудника'}
              disabled={busy || showCreateForm} onClick={() => setShowCreateForm(true)}>
        <Icon name="plus"/><span>Добавить<span className="wide-label"> {organization ? 'организацию' : 'сотрудника'}</span></span>
      </button>
    </div>
    {!view && <ErrorMessage error={error}/>}
    {message && <p className="success" role="status">{message}</p>}
    {showCreateForm && <section className="panel form-panel">
      <EntityForm entity={entity} initial={null} busy={busy} onSave={data => save(data, null)}
                  onCancel={() => setShowCreateForm(false)}/>
    </section>}
    {view && <dialog className="edit-dialog" ref={dialog} aria-labelledby={dialogHeadingId}
                     onCancel={event => {
                       event.preventDefault();
                       dismiss();
                     }}>
      {view.mode === 'edit' ?
        <EntityForm entity={entity} initial={view.record} busy={busy} headingId={dialogHeadingId}
                    onSave={data => save(data, view.record)} onCancel={dismiss}>
          <ErrorMessage error={error}/>
        </EntityForm> : <>
          <h2 id={dialogHeadingId} tabIndex={-1}>{organization ? 'Информация об организации' : 'Информация о сотруднике'}</h2>
          <dl className="record-details">
            {recordFields[entity].map(field => <div key={field}>
              <dt>{recordLabel(entity, field)}</dt><dd>{display(view.record, field)}</dd>
            </div>)}
          </dl>
          <ErrorMessage error={error}/>
          <div className="actions">
            <button className="primary" disabled={busy} onClick={() => edit(view.record)}>Изменить</button>
            <button className="danger" disabled={busy} onClick={() => remove(view.record)}>Удалить</button>
            <button disabled={busy} onClick={dismiss}>Закрыть</button>
          </div>
        </>}
    </dialog>}
    <div className="workspace">
      <aside aria-label="Поиск и параметры списка">
        <details className="panel side-panel" open={wideLayout}>
          <summary><Icon name="search"/><span>Поиск по ID</span><Icon name="expand"/></summary>
          <form className="lookup" onSubmit={event => {
            event.preventDefault();
            const submitter = (event.nativeEvent as SubmitEvent).submitter;
            find(document.activeElement === lookupInput.current ? lookupInput.current : submitter ?? lookupInput.current);
          }}>
            <input ref={lookupInput} aria-label="ID записи" placeholder="ID записи" required value={lookupId} inputMode="numeric"
                   onChange={event => setLookupId(event.target.value)}/>
            <button disabled={busy}>Найти</button>
          </form>
        </details>
        <details className="panel side-panel" open={wideLayout}>
          <summary><Icon name="sliders"/><span>Параметры<span className="wide-label"> списка</span></span><Icon name="expand"/></summary>
          <QueryForm entity={entity} query={query} busy={busy} onApply={applyQuery}/>
        </details>
      </aside>
      <div className="records-column">
        <section className="panel" aria-busy={busy}>
          <div className="panel-heading">
            <h2><Icon name="list"/>Записи {loadedQuery && <span className="count" aria-label={`Всего записей ${total}`}>{total}</span>}</h2>
            <button className="refresh" ref={refreshButton} disabled={busy} aria-label="Обновить список"
                    onClick={() => run(() => load())}><Icon name="refresh"/>Обновить</button>
          </div>
          {busy && <p className="load-state" role="status">Загрузка…</p>}
          {loadedQuery ? <>
            <RecordTable records={records} entity={entity} busy={busy} actions={{info, edit, remove}}/>
            <div className="pagination">
              <span>Страница <b>{loadedQuery.page}</b> из {String(pages || 1n)}</span>
              <div className="actions">
                <button disabled={busy || BigInt(loadedQuery.page) <= 1n} onClick={() => changePage(-1n)}>Назад</button>
                <button disabled={busy || BigInt(loadedQuery.page) >= pages} onClick={() => changePage(1n)}>Вперёд</button>
              </div>
            </div>
          </> : !busy && <p className="load-state">Список не загружен</p>}
        </section>
      </div>
    </div>
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
    <div className="page-heading"><h1>Операции</h1></div>
    <ErrorMessage error={error}/>
    {message && <p className="success" role="status">{message}</p>}
    {result && <section className="panel result-panel"><h2 className="panel-heading">Результат поглощения</h2>
      <RecordTable entity="organizations" records={[result]} busy={busy}/></section>}
    <div className="operations-grid">
    <section>
      <h2>Подсчет организаций</h2>
      <p>Типы по возрастанию</p>
      <ol className="type-order">{organizationTypes.map(type => <li key={type}>{type}</li>)}</ol>
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
      <h2>Удаление одной организации по типу</h2>
      <div className="query-row"><label>Тип<select disabled={busy} value={deleteType}
                        onChange={event => setDeleteType(event.target.value)}>{typeOptions}</select></label>
      <button className="danger" disabled={busy} onClick={removeByType}>Удалить по типу</button></div>
    </section>
    <section>
      <h2>Найм сотрудника</h2>
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
      <h2>Поглощение организации</h2>
      <p>Сотрудники перейдут в первую организацию, а вторая будет удалена.</p>
      <p>При ошибке проверьте данные и укажите те же ID для продолжения.</p>
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
    </div>
    {busy && <p role="status">Загрузка…</p>}
  </>;
}

export default function App() {
  const [tab, setTab] = useState<Entity | 'operations'>('organizations');
  const tabs = {organizations: 'Организации', employees: 'Сотрудники', operations: 'Операции'} as const;
  return <>
    <a className="skip-link" href="#content">К содержанию</a>
    <header className="masthead">
      <div className="masthead-inner">
        <div className="brand"><span className="brand-mark" aria-hidden="true"><i/><i/><i/></span>Реестр</div>
        <nav aria-label="Разделы реестра">
          {(Object.keys(tabs) as (keyof typeof tabs)[]).map(key =>
            <button key={key} aria-current={tab === key ? 'page' : undefined} onClick={() => setTab(key)}>
              <Icon name={key}/>{tabs[key]}
            </button>)}
        </nav>
      </div>
    </header>
    <main id="content" tabIndex={-1}>
      {tab === 'operations' ? <Operations/> : <Collection key={tab} entity={tab}/>}
    </main>
  </>;
}
