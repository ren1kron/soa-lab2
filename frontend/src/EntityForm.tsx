import {useState} from 'react';
import {type Entity, type XmlRecord, valueAt, organizationTypes} from './api';

export default function EntityForm({entity, initial, busy, onSave, onCancel}: {
  entity: Entity; initial: XmlRecord | null; busy: boolean;
  onSave: (data: XmlRecord) => Promise<void>; onCancel: () => void;
}) {
  const employee = entity === 'employees';
  const [values, setValues] = useState<Record<string, string>>(() => {
    const fields = [
      'name', 'coordinates.x', 'coordinates.y', 'annualTurnover', 'type', 'organizationId',
      'officialAddress.zipCode', 'officialAddress.town.x', 'officialAddress.town.y', 'officialAddress.town.name'
    ];
    const values: Record<string, string> = {};
    for (const field of fields) values[field] = initial ? valueAt(initial, field) : '';
    return values;
  });
  const [address, setAddress] = useState(!!initial?.officialAddress);
  const [town, setTown] = useState(!!valueAt(initial ?? {}, 'officialAddress.town.x'));
  const input = (field: string, label: string, required = false) => <label>{label}
    <input value={values[field]} required={required}
           onChange={event => setValues({...values, [field]: event.target.value})}/>
  </label>;

  function submit() {
    const data: XmlRecord = {name: values.name};
    if (employee) {
      if (values.organizationId !== '') data.organizationId = values.organizationId;
    } else {
      data.coordinates = {x: values['coordinates.x'], y: values['coordinates.y']};
      if (values.annualTurnover !== '') data.annualTurnover = values.annualTurnover;
      if (values.type !== '') data.type = values.type;
      if (address) {
        const officialAddress: XmlRecord = {zipCode: values['officialAddress.zipCode']};
        if (town) officialAddress.town = {
          x: values['officialAddress.town.x'],
          y: values['officialAddress.town.y'],
          name: values['officialAddress.town.name'],
        };
        data.officialAddress = officialAddress;
      }
    }
    onSave(data);
  }

  return <section>
    <h2>{initial ? `Изменение записи с ID ${valueAt(initial, 'id')}` : employee ? 'Новый сотрудник' : 'Новая организация'}</h2>
    <form onSubmit={event => {
      event.preventDefault();
      submit();
    }}>
      <fieldset disabled={busy}>
        <div className="form-grid">
          {input('name', employee ? 'Имя *' : 'Название *', true)}
          {employee ? input('organizationId', 'ID работодателя') : <>
            {input('coordinates.x', 'Координата X *', true)}
            {input('coordinates.y', 'Координата Y *', true)}
            {input('annualTurnover', 'Годовой оборот больше нуля')}
            <label>Тип<select value={values.type} onChange={event => setValues({...values, type: event.target.value})}>
              <option value="">Не указан</option>
              {organizationTypes.map(type => <option key={type}>{type}</option>)}
            </select></label>
          </>}
        </div>
        {!employee && <>
            <label className="check">
                <input type="checkbox" checked={address} onChange={event => setAddress(event.target.checked)}/>
                Указать адрес
            </label>
          {address && <div className="nested">
            {input('officialAddress.zipCode', 'Почтовый индекс до 18 символов')}
              <label className="check">
                  <input type="checkbox" checked={town} onChange={event => setTown(event.target.checked)}/>
                  Указать город
              </label>
            {town && <div className="form-grid">
              {input('officialAddress.town.name', 'Название города')}
              {input('officialAddress.town.x', 'X города *', true)}
              {input('officialAddress.town.y', 'Y города *', true)}
            </div>}
          </div>}
        </>}
        <p className="muted">Поля со звездочкой нужно заполнить</p>
        {employee && <p className="muted">Для сотрудника без работы оставьте ID работодателя пустым</p>}
        <div className="actions">
          <button type="submit">Сохранить</button>
          <button type="button" onClick={onCancel}>Отмена</button>
        </div>
      </fieldset>
    </form>
  </section>;
}
