import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'../..');
const read=p=>readFileSync(resolve(root,p),'utf8');
const manifest=JSON.parse(read('deploy/database/manifest.json'));
const allowed=new Set(manifest.tables);
const walk=p=>existsSync(resolve(root,p))?readdirSync(resolve(root,p),{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(p+'/'+e.name):[p+'/'+e.name]):[];
const strip=s=>s.replace(/'(?:''|\\.|[^'])*'|"(?:\\.|[^"])*"|\/\*[\s\S]*?\*\/|--[^\n]*|\/\/[^\n]*/g,m=>m.startsWith("'")||m.startsWith('"')?m:' ');
const schema=strip(read(manifest.schema));
const created=[...schema.matchAll(/\bCREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+\x60?(\w+)/gi)].map(m=>m[1]);
assert.deepEqual([...created].sort(),[...allowed].sort(),'Schema tables must exactly match ownership manifest');
assert.equal(new Set(created).size,created.length,'Duplicate CREATE TABLE');
assert(!/\b(?:USE|CREATE\s+DATABASE|DROP\s+(?:TABLE|DATABASE))\b/i.test(schema),'Schema must not choose/create/drop a database');
assert(!/\bcbdb\s*\./i.test(schema),'Hard-coded shared database');
if(['user','user-behavior-track'].includes(manifest.project)) assert(!/\b(?:INSERT\s+INTO|REPLACE\s+INTO|UPDATE\s+\w+|DELETE\s+FROM)\b/i.test(schema.replace(/ON UPDATE CURRENT_TIMESTAMP/gi,'')),'Schema must not include demo seeds or grants');
for(const p of [...manifest.fullInstall,...manifest.manualBootstrap,...manifest.upgradeCandidates]) {
 assert(existsSync(resolve(root,p)),'Missing deployment file '+p);
 assert(!p.includes('database-archive'),'Archive cannot be executable input');
 const sql=strip(read(p));
 const temporary=new Set([...sql.matchAll(/CREATE\s+TEMPORARY\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+\x60?(\w+)/gi)].map(m=>m[1]));
 const aliases=new Set([...sql.matchAll(/(?:FROM|JOIN)\s+\x60?(\w+)\x60?\s+(?:AS\s+)?(\w+)/gi)].filter(m=>allowed.has(m[1])||temporary.has(m[1])).map(m=>m[2]));
 assert(!/\bcbdb\s*\./i.test(sql),'Hard-coded database: '+p);
 for(const m of sql.replace(/ON\s+DUPLICATE\s+KEY\s+UPDATE/gi,'DUPLICATE_ASSIGNMENT').matchAll(/\b(?:CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?|ALTER\s+TABLE|INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+\x60?(\w+)/gi)) {
   if(['IF','CURRENT_TIMESTAMP','SET','CASCADE','RESTRICT'].includes(m[1].toUpperCase())) continue;
   assert(allowed.has(m[1])||temporary.has(m[1])||aliases.has(m[1]),'Foreign write target '+m[1]+' in '+p);
 }
}
for(const p of walk(manifest.sourceRoot).filter(p=>p.endsWith('.java'))) {
 for(const m of read(p).matchAll(/@TableName\("([^"]+)"\)/g)) assert(allowed.has(m[1]),'Foreign entity '+m[1]+' in '+p);
}
if(manifest.readOnlyExternal) {
 const ext=manifest.readOnlyExternal;
 for(const p of [...walk(ext.javaRoot),...walk(ext.xmlRoot)]) {
   const s=strip(read(p)).replace(/<!--[\s\S]*?-->/g,' ');
   assert(!/@(?:Insert|Update|Delete)\s*\(|<(?:insert|update|delete)\b|\b(?:INSERT\s+INTO|UPDATE\s+mg_|DELETE\s+FROM)\b/i.test(s),'External reader writes: '+p);
   for(const m of s.matchAll(/\b(?:FROM|JOIN)\s+\x60?(\w+)/gi)) assert(ext.tables.includes(m[1]),'Unexpected external table '+m[1]);
 }
}
console.log('PASS '+manifest.project+': '+created.length+' owned tables; deployment files, entities and external-read boundary checked. No SQL executed.');
