import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { Decimal, formatJson, parseJson } from './json.mjs';

describe('expected.json text', () => {
  it('writes one member per line with the given indent', () => {
    assert.equal(
      formatJson({ name: 'a', list: [1, [], {}], nothing: null, yes: true }),
      '{\n  "name": "a",\n  "list": [\n    1,\n    [],\n    {}\n  ],\n  "nothing": null,\n  "yes": true\n}',
    );
  });

  it('writes and reads an unsigned 64-bit decode time exactly', () => {
    const text = formatJson({ tfdt: 18446744073709550592n });

    assert.equal(text, '{\n  "tfdt": 18446744073709550592\n}');
    assert.equal(parseJson(text).tfdt, 18446744073709550592n);
    assert.equal(parseJson('{"small": 480480}').small, 480480);
  });

  it('writes a decimal with a fractional digit even when it is whole', () => {
    assert.equal(formatJson([new Decimal(0), new Decimal(30.03), new Decimal(-0.042667), new Decimal(-0)]),
      '[\n  0.0,\n  30.03,\n  -0.042667,\n  -0.0\n]');
    assert.throws(() => formatJson(new Decimal(1e-7)), RangeError);
    assert.throws(() => new Decimal(Number.NaN), RangeError);
  });

  it('escapes every character outside printable ASCII', () => {
    assert.equal(formatJson('xé−\n"'), '"x\\u00e9\\u2212\\n\\""');
  });

  it('refuses values that JSON cannot hold exactly', () => {
    assert.throws(() => formatJson(0.5), RangeError);
    assert.throws(() => formatJson({ missing: undefined }), TypeError);
    assert.throws(() => formatJson(new Map()), TypeError);
  });
});
