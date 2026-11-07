import assert from 'node:assert/strict';
import test from 'node:test';
import {serviceUrl} from '../src/service-url.ts';

test('keeps the existing Node proxy routes when origins are absent', () => {
  assert.equal(serviceUrl('/organizations?size=1', false), '/api/organizations/organizations?size=1');
  assert.equal(serviceUrl('/orgmanager/hire/1?employeeId=2', true), '/api/manager/orgmanager/hire/1?employeeId=2');
});

test('uses separate browser-visible HTTPS origins for static deployment', () => {
  const origins = {organizations: 'https://localhost:36125/', manager: 'https://localhost:36126'};
  assert.equal(serviceUrl('/organizations/9223372036854775807', false, origins),
    'https://localhost:36125/organizations/9223372036854775807');
  assert.equal(serviceUrl('/orgmanager/hire/1?employeeId=2', true, origins),
    'https://localhost:36126/orgmanager/hire/1?employeeId=2');
});

test('rejects non-HTTPS or non-origin configuration', () => {
  for (const organizations of ['http://localhost:26125', '/api', 'https://user:secret@localhost',
    'https://localhost/path', 'https://localhost?x=1', 'https://localhost/#x']) {
    assert.throws(() => serviceUrl('/organizations', false, {organizations}));
  }
});
