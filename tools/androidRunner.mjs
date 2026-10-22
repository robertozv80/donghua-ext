#!/usr/bin/env node
/**
 * AndroidRunner: smoke harness para CloudStream en un dispositivo real.
 *
 * No instala, desinstala ni cambia permisos. Puede lanzar una Activity y
 * capturar logcat durante una ventana fija o continuamente hasta Ctrl+C.
 */
import { spawn, spawnSync } from 'node:child_process';
import { createWriteStream, readFileSync, writeFileSync } from 'node:fs';

const args = process.argv.slice(2);
const value = (name, fallback = '') => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};
const has = name => args.includes(name);
const serial = value('--serial', process.env.ANDROID_SERIAL || '');
const pkg = value('--package', process.env.ANDROID_PACKAGE || '');
const activity = value('--activity', process.env.ANDROID_ACTIVITY || '');
const video = value('--video', '');
const url = value('--url', '');
const seconds = Number(value('--seconds', process.env.ANDROID_RUN_SECONDS || '20'));
const capture = has('--capture');
const full = has('--full');
const output = value('--output', process.env.ANDROID_RUN_OUTPUT || 'androidRunner.log');

function adb(adbArgs, allowFailure = false) {
  const fullArgs = [...(serial ? ['-s', serial] : []), ...adbArgs];
  const r = spawnSync('adb', fullArgs, { encoding: 'utf8' });
  if (r.error) throw new Error(`adb no está disponible: ${r.error.message}`);
  if (r.status !== 0 && !allowFailure) {
    throw new Error(`adb ${fullArgs.join(' ')}\n${(r.stderr || r.stdout || '').trim()}`);
  }
  return (r.stdout || '').trim();
}

function summarize(file) {
  const text = readFileSync(file, 'utf8');
  const lines = text.split(/\r?\n/).filter(Boolean);
  const relevant = lines.filter(line => /cloudstream|exoplayer|extractor|dailymotion|animecube|rumble|ok\.ru|audio|aac|mediacodec|m3u8|exception|error|failed|source|quality|url/i.test(line));
  const summary = `${relevant.join('\n')}\n`;
  const summaryFile = file.replace(/(\.[^.]+)?$/, '.summary.log');
  writeFileSync(summaryFile, summary, 'utf8');
  console.log(`SUMMARY: ${summaryFile} (${relevant.length}/${lines.length} líneas relevantes)`);
  return { lines, relevant };
}

function usage() {
  console.log(`Uso: node tools/androidRunner.mjs [opciones]\n\n` +
    `  --list                         lista devices conectados\n` +
    `  --package PKG                  package de CloudStream/app de prueba\n` +
    `  --activity COMPONENT           Activity explícita (opcional)\n` +
    `  --video ID | --url URL         extras para la Activity\n` +
    `  --seconds N                    ventana fija (default 20)\n` +
    `  --capture                      captura continua hasta Ctrl+C\n` +
    `  --full                         captura todos los tags de logcat\n` +
    `  --output FILE                  archivo de captura (default androidRunner.log)\n` +
    `  --serial SERIAL                device elegido\n\n` +
    `Sin --full se usa un filtro acotado; --full es recomendado para diagnóstico.`);
}

if (has('--help') || has('-h')) { usage(); process.exit(0); }
try {
  if (has('--list')) {
    console.log(adb(['devices', '-l']));
    process.exit(0);
  }
  if (!pkg) { usage(); process.exit(2); }
  const state = adb(['get-state'], true);
  if (state !== 'device') throw new Error(`device no listo: ${state || 'sin respuesta'}`);

  adb(['logcat', '-c']);
  if (activity) {
    const component = activity.includes('/') ? activity : `${pkg}/${activity}`;
    const am = ['shell', 'am', 'start', '-W', '-n', component];
    if (url) am.push('--es', 'url', url);
    if (video) am.push('--es', 'video', video);
    am.push('--ez', 'androidRunner', 'true');
    console.log('START:', adb(am, true));
  } else {
    console.log('INFO: no se pasó --activity; se captura logcat sin lanzar Activity.');
  }

  const filter = full ? '*:V' : '*:S CloudStream:D ExoPlayer:D Extractor:D AndroidRunner:D';
  if (capture) {
    const file = createWriteStream(output, { flags: 'w' });
    const child = spawn('adb', [...(serial ? ['-s', serial] : []), 'logcat', '-v', 'threadtime', filter], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    child.stdout.pipe(file);
    child.stderr.pipe(process.stderr);
    console.log(`CAPTURING: ${output} (${full ? 'FULL' : 'FILTERED'})`);
    console.log('Realiza manualmente la prueba; pulsa Ctrl+C para terminar.');
    const stop = () => {
      child.kill('SIGTERM');
      file.end(() => {
        try { summarize(output); } catch (e) { console.error(`Resumen no generado: ${e.message}`); }
        process.exit(0);
      });
    };
    process.once('SIGINT', stop);
    process.once('SIGTERM', stop);
    child.once('error', error => {
      file.end();
      console.error(`AndroidRunner FAIL: ${error.message}`);
      process.exit(1);
    });
    child.once('exit', code => {
      file.end();
      if (code && code !== 0) process.exit(code);
    });
  } else {
    if (seconds > 0) spawnSync(process.execPath, ['-e', `setTimeout(() => {}, ${Math.min(seconds, 300)} * 1000)`], { stdio: 'ignore' });
    const r = spawnSync('adb', [...(serial ? ['-s', serial] : []), 'logcat', '-d', '-v', 'brief', filter], { encoding: 'utf8' });
    const lines = (r.stdout || '').split(/\r?\n/).filter(Boolean);
    const audio = lines.filter(line => /audio|aac|mp4_h264_aac|audioTrack|MediaCodec/i.test(line));
    const errors = lines.filter(line => /error|exception|failed|fatal|no audio|without audio/i.test(line));
    console.log(`LOGCAT lines=${lines.length} audioMarkers=${audio.length} errorMarkers=${errors.length}`);
    if (audio.length) console.log(`--- audio markers ---\n${audio.slice(-80).join('\n')}`);
    if (errors.length) console.log(`--- possible errors ---\n${errors.slice(-80).join('\n')}`);
    if (seconds > 0) console.log(`Ventana observada: ${Math.min(seconds, 300)}s.`);
    process.exit(errors.some(line => /no audio|without audio|fatal exception/i.test(line)) ? 1 : 0);
  }
} catch (e) {
  console.error('AndroidRunner FAIL:', e.message);
  process.exit(1);
}
