// Emits config/schema.json (JSON Schema for editor autocomplete) from the zod schema.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { z } from 'zod';
import { appConfigSchema } from '@movy/config';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const schema = z.toJSONSchema(appConfigSchema, { io: 'input', unrepresentable: 'any' });
schema.title = 'Movy app config';
fs.writeFileSync(path.join(root, 'config', 'schema.json'), JSON.stringify(schema, null, 2) + '\n');
console.log('wrote config/schema.json');
