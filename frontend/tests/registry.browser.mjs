import assert from 'node:assert/strict';
import {after, before, test} from 'node:test';
import {fileURLToPath} from 'node:url';
import {chromium} from 'playwright-core';

const origin = 'https://registry.test';
const maxId = '9223372036854775807';
const minCoordinate = '-9223372036854775808';
const name = 'Научно-исследовательский институт машиностроения';
const organization = `<organization><id>${maxId}</id><name>${name}</name>
  <coordinates><x>${minCoordinate}</x><y>0</y></coordinates><creationDate>2026-11-22</creationDate>
  <employeeCount>0</employeeCount></organization>`;
const employee = '<employee><id>5</id><name>Юлия</name></employee>';
let browser;

before(async () => { browser = await chromium.launch({channel: 'chrome'}); });
after(async () => { await browser?.close(); });

async function openRegistry(t, width, handleApi) {
  const page = await browser.newPage({viewport: {width, height: 850}});
  page.setDefaultTimeout(5000);
  t.after(() => page.close());
  await page.route(origin + '/**', route => {
    const {pathname} = new URL(route.request().url());
    if (pathname.startsWith('/api/')) return handleApi(route, pathname);
    const file = pathname === '/' ? 'index.html' : pathname.slice(1);
    return route.fulfill({path: fileURLToPath(new URL('../dist/' + file, import.meta.url))});
  });
  await page.goto(origin);
  return page;
}

for (const width of [1440, 768, 390, 320]) {
  test(`registry rows and read-only details at ${width}px`, async t => {
    const page = await openRegistry(t, width, (route, pathname) => {
      const xml = {
        '/api/organizations/organizations': '<organizations>' + organization + '</organizations>',
        ['/api/organizations/organizations/' + maxId]: organization,
        '/api/organizations/employees': '<employees>' + employee + '</employees>',
        ['/api/manager/orgmanager/acquise/' + maxId + '/2']:
          '<acquisition>' + organization + '<acquiredId>2</acquiredId><employeeCount>0</employeeCount></acquisition>',
      }[pathname];
      assert.ok(xml, 'Unexpected API request ' + pathname);
      return route.fulfill({body: xml, headers: {'Content-Type': 'application/xml', 'X-Total-Count': '1'}});
    });

    const dialog = page.getByRole('dialog');
    const button = (scope, name) => scope.getByRole('button', {name, exact: true});
    async function checkTable(table, withActions) {
      await table.getByText(name, {exact: true}).waitFor();
      const values = [maxId, name, 'Не указан', '0', '—', '22-11-2026', minCoordinate, '0', '—', '—', '—', '—'];
      assert.equal(await table.getByRole('columnheader').count(), values.length + Number(withActions));
      const cells = table.locator('tbody tr').getByRole('cell');
      assert.deepEqual((await cells.allTextContents()).slice(0, 12), values);
      for (const cell of [table.getByRole('columnheader', {name: 'ID', exact: true}), cells.first()]) {
        assert.equal(await cell.evaluate(el => {
          const range = document.createRange();
          range.selectNodeContents(el);
          return range.getClientRects().length;
        }), 1);
      }
      if (width <= 390) {
        assert.ok(await table.evaluate(el => el.parentElement.scrollWidth > el.parentElement.clientWidth));
      }
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    }

    const list = page.getByRole('table', {name: 'Организации'});
    await checkTable(list, true);
    await list.getByText(name, {exact: true}).dblclick();
    await dialog.getByText(minCoordinate, {exact: true}).waitFor();
    assert.equal(await dialog.locator('dd').count(), 12);
    assert.equal(await dialog.getByRole('textbox').count(), 0);
    await button(dialog, 'Изменить').click();
    assert.equal(await dialog.getByLabel('Координата X *').inputValue(), minCoordinate);
    await page.keyboard.press('Escape');
    await dialog.getByText(name, {exact: true}).waitFor();
    await button(dialog, 'Закрыть').click();
    assert.ok(await list.getByText(name, {exact: true}).evaluate(el => el === document.activeElement));

    const edit = button(list, 'Изменить');
    await edit.press('Enter');
    await dialog.getByLabel('Координата X *').waitFor();
    await page.keyboard.press('Escape');
    await dialog.waitFor({state: 'hidden'});
    assert.ok(await edit.evaluate(el => el === document.activeElement));

    const lookup = page.getByRole('textbox', {name: 'ID записи'});
    if (width <= 960) await page.locator('summary').filter({hasText: 'Поиск по ID'}).press('Enter');
    await lookup.fill(maxId);
    await button(page, 'Найти').click();
    await dialog.getByText(maxId, {exact: true}).waitFor();
    await page.keyboard.press('Escape');
    assert.equal(await page.getByRole('table').count(), 1);

    const nav = page.getByRole('navigation');
    await nav.getByRole('button', {name: 'Сотрудники'}).click();
    const employees = page.getByRole('table', {name: 'Сотрудники'});
    await employees.getByText('Юлия').waitFor();
    assert.ok(await employees.evaluate(el => el.clientWidth >= el.parentElement.clientWidth - 1));
    const actions = employees.locator('tbody button');
    assert.deepEqual(await actions.evaluateAll(items => items.map(item => item.getAttribute('aria-label'))),
      ['Информация', 'Изменить', 'Удалить']);
    await employees.getByText('Юлия').dblclick();
    await dialog.getByText('Юлия').waitFor();
    assert.deepEqual(await dialog.locator('dd').allTextContents(), ['5', 'Юлия', '—']);
    await button(dialog, 'Закрыть').press('Enter');
    await button(employees, 'Информация').press('Enter');
    await dialog.waitFor();
    await page.keyboard.press('Escape');

    await nav.getByRole('button', {name: 'Операции'}).click();
    await page.getByLabel('ID первой организации').fill(maxId);
    await page.getByLabel('ID второй организации').fill('2');
    page.once('dialog', confirmation => confirmation.accept());
    await page.getByRole('button', {name: 'Выполнить поглощение'}).click();
    const result = page.getByRole('table');
    await result.waitFor();
    assert.equal(await result.getByRole('button', {name: 'Изменить'}).count(), 0);
    await checkTable(result, false);
  });
}

test('save shows the returned record and refreshes the filtered employee list', async t => {
  const collection = '/api/organizations/employees';
  const item = collection + '/' + maxId;
  const writes = [];
  const listQueries = [];
  let saved = '';
  let matches = false;
  const page = await openRegistry(t, 1200, (route, path) => {
    const request = route.request();
    const respond = (body, status = 200) => route.fulfill({status, body, contentType: 'application/xml'});
    if (path === '/api/organizations/organizations') return respond('<organizations/>');
    if (path === collection && request.method() === 'GET') {
      listQueries.push(Object.fromEntries(new URL(request.url()).searchParams));
      return route.fulfill({body: '<employees>' + (matches ? saved : '') + '</employees>',
        headers: {'Content-Type': 'application/xml', 'X-Total-Count': matches ? '1' : '0'}});
    }
    if (request.method() === 'GET' && path === item) return respond(saved);
    writes.push([request.method(), path, request.postData()]);
    if (request.method() === 'POST' && path === collection) {
      saved = '<employee><id>' + maxId + '</id><name>Анна</name></employee>';
      return respond(saved, 201);
    }
    if (request.method() === 'PUT' && path === item) {
      if (request.postData().includes('<name>Ошибка</name>')) return respond('<error><code>VALIDATION_FAILED</code></error>', 422);
      saved = '<employee><id>' + maxId + '</id><name>Юлия Иванова</name></employee>';
      matches = true;
      return respond(saved);
    }
    assert.equal(path, item);
    assert.equal(request.method(), 'DELETE');
    matches = false;
    return route.fulfill({status: 204});
  });
  const button = (scope, name) => scope.getByRole('button', {name, exact: true});
  const dialog = page.getByRole('dialog');
  await button(page.getByRole('navigation'), 'Сотрудники').click();
  await page.getByText('Записей нет', {exact: true}).waitFor();
  await button(page, 'Добавить фильтр').click();
  await page.getByLabel('Значение 1', {exact: true}).fill('Юлия');
  await page.getByLabel('Направление 1').selectOption('desc');
  await page.getByLabel('Записей на странице').fill('5');
  await button(page, 'Применить').click();

  const add = button(page, 'Добавить сотрудника');
  await add.click();
  await page.getByLabel('Имя *').fill('Анна');
  await button(page, 'Сохранить').click();
  await dialog.getByText(maxId, {exact: true}).waitFor();
  assert.equal(await dialog.getByText('Анна', {exact: true}).isVisible(), true);
  await button(dialog, 'Закрыть').click();
  assert.ok(await add.evaluate(el => el === document.activeElement));
  assert.equal(await page.getByRole('table').count(), 1);
  assert.ok(await page.getByText('Записей нет', {exact: true}).isVisible());

  await page.getByLabel('ID записи').fill(maxId);
  await button(page, 'Найти').click();
  await button(dialog, 'Изменить').click();
  const editName = dialog.getByLabel('Имя *');
  await editName.fill('Ошибка');
  await button(dialog, 'Сохранить').click();
  await dialog.getByRole('alert').waitFor();
  assert.equal(await editName.inputValue(), 'Ошибка');
  await editName.fill('Юлия Иванова');
  await button(dialog, 'Сохранить').click();
  await dialog.getByText('Юлия Иванова', {exact: true}).waitFor();
  assert.equal(await dialog.getByRole('textbox').count(), 0);
  page.once('dialog', confirmation => confirmation.dismiss());
  await button(dialog, 'Удалить').click();
  assert.ok(await dialog.isVisible());
  await button(dialog, 'Закрыть').click();
  const list = page.getByRole('table');
  await list.getByText('Юлия Иванова', {exact: true}).waitFor();
  assert.equal(await page.getByRole('table').count(), 1);
  page.once('dialog', confirmation => confirmation.accept());
  await button(list, 'Удалить').click();
  await page.getByText('Записей нет', {exact: true}).waitFor();
  assert.ok(await button(page, 'Обновить список').evaluate(el => el === document.activeElement));
  assert.deepEqual(writes, [
    ['POST', collection, '<employee><name>Анна</name></employee>'],
    ['PUT', item, '<employee><name>Ошибка</name></employee>'],
    ['PUT', item, '<employee><name>Юлия Иванова</name></employee>'],
    ['DELETE', item, null],
  ]);
  const filtered = {page: '1', size: '5', sort: '-id', filter: 'name:contains:Юлия'};
  assert.deepEqual(listQueries.slice(1), [filtered, filtered, filtered, filtered]);
});
