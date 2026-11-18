'use strict';

const fs = require('node:fs');
const path = require('node:path');
const STRESSES = ['rotate', 'background', 'chat-switch', 'network', 'stream-send'];

function inspect(directory, journey, sha) {
  const errors = [];
  const evidence = [];
  const fail = message => errors.push(`BREAK_IT_FAILED: ${journey}: ${message}`);
  try {
    const root = path.join(directory, 'break-it');
    const report = JSON.parse(fs.readFileSync(path.join(root, 'result.json'), 'utf8'));
    const baselineSha = fs.readFileSync(path.join(directory, 'app-apk-sha256.txt'), 'utf8').split(/\s+/)[0];
    if (report.journey !== journey || report.sourceSha !== sha || report.apkSha256 !== baselineSha || !/^[a-f0-9]{64}$/.test(baselineSha)) fail('stress APK does not match the normal head journey');
    if (report.baselinePassed !== true || report.driverExit !== 0 || report.elapsedSeconds > 1200 || !(report.elapsedSeconds > 0)) fail('normal journey or stressed replay did not pass within 20 minutes');
    const transcript = fs.readFileSync(path.join(root, 'driver-transcript.txt'), 'utf8');
    if (!/^DRIVER EXIT: 0$/m.test(transcript) || /^DRIVER EXIT: [1-9]/m.test(transcript)) fail('stressed journey did not finish successfully');
    for (const stress of STRESSES) {
      try {
        const checkpoint = JSON.parse(fs.readFileSync(path.join(root, `${stress}.json`), 'utf8'));
        const png = fs.readFileSync(path.join(root, `${stress}.png`));
        const xml = fs.readFileSync(path.join(root, `${stress}.xml`), 'utf8');
        const text = fs.readFileSync(path.join(root, `${stress}.txt`), 'utf8').trim();
        if (checkpoint.stress !== stress || checkpoint.passed !== true || !text || !transcript.includes(`BREAK_IT ${stress} PASSED`) || png.length < 100 || !png.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')) || !xml.includes('<hierarchy') || !xml.includes('dev.multiprompt.companion')) throw new Error('invalid checkpoint');
        evidence.push(`break-it/${stress}.txt: ${text.slice(0, 600)} (PNG/XML retained)`);
      } catch {
        fail(`${stress} checkpoint missing, failed or missing screenshot/text evidence`);
      }
    }
  } catch {
    fail('retained break-it/result.json and replay evidence are required after the normal journey passes');
  }
  return { errors, evidence };
}

module.exports = { STRESSES, inspect };
