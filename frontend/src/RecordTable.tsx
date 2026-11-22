import {display, recordFields, recordLabel, valueAt, type Entity, type XmlRecord} from './api';
import Icon from './Icon';

type RowAction = (record: XmlRecord, trigger: HTMLButtonElement) => void;
type Actions = {
  info: (record: XmlRecord, trigger: HTMLElement) => void;
  edit: RowAction;
  remove: RowAction;
};
type Props = {entity: Entity; busy: boolean; actions?: Actions};

const numericFields = new Set(['id', 'employeeCount', 'annualTurnover', 'coordinates.x', 'coordinates.y',
  'officialAddress.town.x', 'officialAddress.town.y', 'organizationId']);

function RecordRow({record, entity, busy, actions}: Props & {record: XmlRecord}) {
  return <tr className={actions && 'editable-row'} onDoubleClick={event => {
    if (busy || !(event.target instanceof Element)) return;
    const cell = event.target.closest('td[data-field]');
    if (cell instanceof HTMLTableCellElement) actions?.info(record, cell);
  }}>
    {recordFields[entity].map(field =>
      <td key={field} tabIndex={actions ? -1 : undefined} data-field={field} className={numericFields.has(field) ? 'numeric' : undefined}>{display(record, field)}</td>
    )}
    {actions && <td className="table-actions"><div>
      <button className="icon-button" aria-label="Информация" title="Информация" disabled={busy}
              onClick={e => actions.info(record, e.currentTarget)}><Icon name="info"/></button>
      <button className="icon-button" aria-label="Изменить" title="Изменить" disabled={busy}
              onClick={e => actions.edit(record, e.currentTarget)}><Icon name="edit"/></button>
      <button className="icon-button danger" aria-label="Удалить" title="Удалить" disabled={busy}
              onClick={e => actions.remove(record, e.currentTarget)}><Icon name="remove"/></button>
    </div></td>}
  </tr>;
}

export default function RecordTable({records, ...props}: Props & {records: XmlRecord[]}) {
  const fields = recordFields[props.entity];
  return <>
    <div className="table-wrap">
      <table className={props.entity} aria-label={props.entity === 'organizations' ? 'Организации' : 'Сотрудники'}>
        <thead><tr>
          {fields.map(field => <th key={field} data-field={field} scope="col" className={numericFields.has(field) ? 'numeric' : undefined}>
            {recordLabel(props.entity, field)}
          </th>)}
          {props.actions && <th scope="col" className="action-heading">Действия</th>}
        </tr></thead>
        <tbody>
          {records.map(record => <RecordRow key={valueAt(record, 'id')} record={record} {...props}/>)}
        </tbody>
      </table>
    </div>
    {records.length === 0 && <p className="empty">Записей нет</p>}
  </>;
}
