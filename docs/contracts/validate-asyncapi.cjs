#!/usr/bin/env node
// Pass the path to an installed @asyncapi/parser package as the first argument.
const path = require('node:path');
const parserPath = process.argv[2] || '@asyncapi/parser';
const { Parser, fromFile } = require(parserPath);

(async () => {
  let warnings = 0;
  for (const service of ['analysis', 'crawler', 'control', 'ingestion']) {
    const file = path.join(__dirname, 'asyncapi', `${service}.json`);
    const { document, diagnostics } = await fromFile(new Parser(), file).parse();
    const errors = diagnostics.filter((entry) => entry.severity === 0);
    warnings += diagnostics.filter((entry) => entry.severity !== 0).length;
    if (!document || errors.length) {
      for (const error of errors) console.error(service, error.message);
      throw new Error(`AsyncAPI parser failed: ${service}`);
    }
    console.log(`PASS: ${service} (${diagnostics.length} non-error diagnostic(s))`);
    for (const diagnostic of diagnostics) {
      console.log(`  ${diagnostic.code}: ${diagnostic.message}`);
    }
  }
  console.log(`AsyncAPI 3.0 validation completed; ${warnings} non-error diagnostic(s).`);
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
