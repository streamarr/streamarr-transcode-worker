// JSON for expected.json: integers of any size stay exact in both directions.

/** A decimal that expected.json prints the way it prints every non-integer: shortest round trip. */
export class Decimal {
  constructor(value) {
    if (!Number.isFinite(value)) {
      throw new RangeError(`not a finite decimal: ${value}`);
    }
    this.value = value;
  }

  toJSONText() {
    if (Object.is(this.value, -0)) {
      return '-0.0';
    }
    if (Number.isInteger(this.value)) {
      return this.value.toFixed(1);
    }
    const text = String(this.value);
    if (text.includes('e')) {
      throw new RangeError(`no plain decimal form for ${this.value}`);
    }
    return text;
  }
}

function escapeString(text) {
  return JSON.stringify(text).replace(
    /[\u007f-￿]/g,
    (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`,
  );
}

function scalar(value) {
  if (value === null) {
    return 'null';
  }
  switch (typeof value) {
    case 'bigint':
      return value.toString();
    case 'boolean':
      return String(value);
    case 'string':
      return escapeString(value);
    case 'number':
      if (!Number.isSafeInteger(value)) {
        throw new RangeError(`only exact integers or Decimal values are written, got ${value}`);
      }
      return String(value);
    default:
      if (value instanceof Decimal) {
        return value.toJSONText();
      }
      return undefined;
  }
}

/**
 * Writes JSON with one member per line and ASCII-only strings, the layout expected.json has
 * always had. Integers print exactly whether they are numbers or BigInts.
 */
export function formatJson(value, indent = 2, depth = 0) {
  const text = scalar(value);
  if (text !== undefined) {
    return text;
  }
  const inner = ' '.repeat(indent * (depth + 1));
  const outer = ' '.repeat(indent * depth);
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return '[]';
    }
    const items = value.map((item) => inner + formatJson(item, indent, depth + 1));
    return `[\n${items.join(',\n')}\n${outer}]`;
  }
  if (typeof value === 'object' && Object.getPrototypeOf(value) === Object.prototype) {
    const entries = Object.entries(value);
    if (entries.some(([, member]) => member === undefined)) {
      throw new TypeError('undefined has no JSON form');
    }
    if (entries.length === 0) {
      return '{}';
    }
    const members = entries.map(
      ([key, member]) => `${inner}${escapeString(key)}: ${formatJson(member, indent, depth + 1)}`,
    );
    return `{\n${members.join(',\n')}\n${outer}}`;
  }
  throw new TypeError(`no JSON form for ${String(value)}`);
}

/** Parses JSON, keeping every integer beyond the exact double range as a BigInt. */
export function parseJson(text) {
  return JSON.parse(text, (_key, value, context) => {
    if (typeof value === 'number' && !Number.isSafeInteger(value) && /^-?\d+$/.test(context.source)) {
      return BigInt(context.source);
    }
    return value;
  });
}
