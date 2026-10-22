#!/usr/bin/env node
/**
 * AndroidRunner: smoke harness para CloudStream en un dispositivo real.
 *
 * No instala, desinstala ni cambia permisos. Solo lanza una Activity mediante
 * `adb shell am start` (si se proporciona --activity) y captura logcat. El
 * usuario puede pasar una URL de episodio o un video ID para que la Activity
 * de prueba lo consuma según su contrato.
 *
 * Ejemplos:
 *   node tools/androidRunner.mjs --list
 *   node tools/androidRunner.mjs --package com.lagradost.cloudstream3
 *   node tools/androidRunner.mjs --package com.lagradost.cloudstream3 \
 *     --activity com.lagradost.cloudstream3.MainActivity --video xbjt74u
 *
 * Variables útiles:
 *   ANDROID_SERIAL, ANDROID_PACKAGE, ANDROID_ACTIVITY, ANDROID_RUN_SECONDS
 */
import { spawnSync } from 'node:child_process';

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

function adb(adbArgs, allowFailure = false) {
  const full = [ ...(serial ? ['-s', serial] : []), ...adbArgs ];
  const r = spawnSync('adb', full, { encoding: 'utf8' });
  if (r.error) throw new Error(`adb no está disponible: ${r.error.message}`);
  if (r.status !== 0 && !allowFailure) {
    throw new Error(`adb ${full.join(' ')}\n${(r.stderr || r.stdout || '').trim()}`);
  }
  return (r.stdout || '').trim();
}

function usage() {
  console.log(`Uso: node tools/androidRunner.mjs [opciones]\n\n` +
    `  --list                         lista devices conectados\n` +
    `  --package PKG                  package de CloudStream/app de prueba\n` +
    `  --activity COMPONENT           Activity explícita (opcional)\n` +
    `  --video ID | --url URL         dato que se entrega como extras\n` +
    `  --seconds N                    ventana de logcat (default 20)\n` +
    `  --serial SERIAL                device elegido\n\n` +
    `No desinstala APKs ni modifica permisos.`);
}

if (has('--help') || has('-h')) { usage(); process.exit(0); }
try {
  if (has('--list')) {
    console.log(adb(['devices', '-l']));
    process.exit(0);
  }
  if (!pkg) { usage(); process.exit(2); }
  const devices = adb(['get-state'], true);
  if (devices !== 'device') throw new Error(`device no listo: ${devices || 'sin respuesta'}`);

  // Limpia solo el buffer de logcat del device para que el resultado sea
  // reproducible; no borra datos de la app.
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

  // Espera a que la Activity llegue a preparar/reproducir el stream. Se usa
  // Node, no `sleep`, para mantener el runner portable en Windows/macOS/Linux.
  if (seconds > 0) spawnSync(process.execPath, ['-e', `setTimeout(() => {}, ${Math.min(seconds, 300)} * 1000)`], { stdio: 'ignore' });
  const filter = '*:S CloudStream:D ExoPlayer:D Extractor:D AndroidRunner:D';
  const r = spawnSync('adb', [...(serial ? ['-s', serial] : []), 'logcat', '-d', '-v', 'brief', filter], { encoding: 'utf8' });
  const log = r.stdout || '';
  const lines = log.split(/\r?\n/).filter(Boolean);
  const audio = lines.filter(l => /audio|aac|mp4_h264_aac|audioTrack|MediaCodec/i.test(l));
  const errors = lines.filter(l => /error|exception|failed|fatal|no audio|without audio/i.test(l));
  console.log(`LOGCAT lines=${lines.length} audioMarkers=${audio.length} errorMarkers=${errors.length}`);
  if (audio.length) { console.log('--- audio markers ---'); console.log(audio.slice(-80).join('\n')); }
  if (errors.length) { console.log('--- possible errors ---'); console.log(errors.slice(-80).join('\n')); }
  if (seconds > 0) console.log(`Ventana observada: ${Math.min(seconds, 300)}s.`);
  process.exit(errors.some(l => /no audio|without audio|fatal exception/i.test(l)) ? 1 : 0);
} catch (e) {
  console.error('AndroidRunner FAIL:', e.message);
  process.exit(1);
}
