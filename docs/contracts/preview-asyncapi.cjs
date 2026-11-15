#!/usr/bin/env node
// Node 22; generator and template may be installed in an isolated temporary directory.
const path = require('node:path');

const [service, outputArgument, moduleRoot] = process.argv.slice(2);
if (!['analysis', 'crawler', 'control', 'ingestion'].includes(service) || !outputArgument || !moduleRoot) {
  console.error('Usage: node docs/contracts/preview-asyncapi.cjs <analysis|crawler|control|ingestion> <output-directory> <node_modules-path>');
  process.exit(1);
}

const output = path.resolve(outputArgument);
const modules = path.resolve(moduleRoot);
const Generator = require(path.join(modules, '@asyncapi/generator'));
const template = path.join(modules, '@asyncapi/html-template');
const inputDirectory = path.join(__dirname, 'asyncapi');

(async () => {
  // The generator resolves nested schema references relative to the current directory.
  process.chdir(inputDirectory);
  const generator = new Generator(template, output);
  await generator.generateFromFile(`${service}.json`);
  console.log(`Preview generated: ${path.join(output, 'index.html')}`);
})().catch((error) => {
  console.error(`Preview failed: ${error.message}`);
  process.exitCode = 1;
});
