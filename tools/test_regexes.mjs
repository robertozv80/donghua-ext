// ============================================================
// test_regexes.mjs — tests reproducibles de las REGEX CRÍTICAS
// de los providers (Dailymotion / Rumble / Ok.ru / gdriveplayer).
//
// Uso (desde la raíz del repo):
//     node tools/test_regexes.mjs
//     exit code != 0 si hay fallos
//
// Requiere JDK en PATH (javac/java): la fase BEHAVIOR compila cada patrón
// con java.util.regex — LA MISMA SEMÁNTICA que usará el app en el JVM.
//
// Fases por caso:
//  1) SYNC     — la regex se extrae del CÓDIGO REAL (por ancla de código).
//                Si la regex desaparece o se mueve, FAIL por DRIFT.
//  2) BEHAVIOR — se compila la literal (escapes Kotlin decodificados) y se
//                comparan los grupos de find() contra muestras reales.
//
// Origen del harness: un fix puntual dejó esta literal con over-escaping:
//     val hlsRel = Regex("HLS=\"([^\"]+\\\\.m3u8)\"")   // 4 backslashes
//     => patrón Java  \\-literal antes de .m3u8  => NUNCA matchea.
// Un validador JS no lo detecta (JS != Java): hay que evaluar con find().
// ============================================================
import { execSync } from 'node:child_process';
import { readFileSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

function kt(rel) { return readFileSync(path.join(ROOT, rel), 'utf8'); }

/** Kotlin "..." → contenido real (escapes \n \t \uXXXX \" \\ ...). */
function kotlinUnescape(s) {
  let out = '';
  let i = 0;
  while (i < s.length) {
    if (s[i] === '\\' && i + 1 < s.length) {
      const c = s[i + 1];
      if (c === 'u') { out += String.fromCharCode(parseInt(s.substr(i + 2, 4), 16)); i += 6; continue; }
      out += ({ n: '\n', t: '\t', r: '\r', b: '\b' }[c] ?? c);
      i += 2;
      continue;
    }
    out += s[i];
    i += 1;
  }
  return out;
}

/**
 * Extrae la literal de la Regex(...) MÁS CERCANA hacia atrás desde `needle`.
 */
function litNear(rel, needle) {
  const src = kt(rel);
  const at = src.indexOf(needle);
  if (at < 0) {
    return { drift: 'ancla no encontrada en ' + rel + '\n   >> ' + needle };
  }
  // elegir la Regex( MAS CERCANA en cualquiera de las dos direcciones
  const back = src.lastIndexOf('Regex(', Math.max(0, at - 1));
  const fwd = src.indexOf('Regex(', at);
  let from = -1;
  if (back >= 0 && (fwd < 0 || at - back <= fwd - at)) from = back;
  else if (fwd >= 0 && fwd - at <= 400) from = fwd;
  if (from < 0) {
    return { drift: 'no hay Regex() cerca del ancla en ' + rel + '\n   >> ' + needle };
  }
  let j = from + 'Regex('.length;
  while (j < src.length && /\s/.test(src[j])) j += 1;
  if (src.startsWith('"""', j)) {
    const end = src.indexOf('"""', j + 3);
    if (end < 0) return { drift: 'literal raw sin cierre en ' + rel + '\n   >> ' + needle };
    return { lit: src.slice(j + 3, end) };
  }
  if (src[j] === '"') {
    let k = j + 1;
    let buf = '';
    while (k < src.length && src[k] !== '"') {
      if (src[k] === '\\') {
        const c = src[k + 1];
        if (c === 'u') { buf += String.fromCharCode(parseInt(src.substr(k + 2, 4), 16)); k += 6; continue; }
        buf += ({ n: '\n', t: '\t', r: '\r', b: '\b' }[c] ?? c);
        k += 2;
        continue;
      }
      buf += src[k];
      k += 1;
    }
    if (k >= src.length) return { drift: 'literal esc sin cierre en ' + rel + '\n   >> ' + needle };
    return { lit: buf };
  }
  return { drift: 'Regex() sin literal de string cerca del ancla en ' + rel + '\n   >> ' + needle };
}

// ==== CASOS ==============================================
// want: array de grupos capturados por find() concatenados con ' | ',
//       o null = la regex NO debe matchear esa entrada.
const TESTS = [
  {
    name: 'CE.dmVideoId: embed normal / geo player / no-match',
    rel: 'common/src/CommonExtractors.kt',
    needle: '\\.html\\?video=|dailymotion\\.com/embed/video/', // file raw chars? corto univoco
    samples: [
      ['https://www.dailymotion.com/embed/video/x9px8ce', ['x9px8ce']],
      ['<iframe src="//geo.dailymotion.com/player.html?video=x9qszae&origin=x">', ['x9qszae']],
      ['<iframe src="//geo.dailymotion.com/player/xk7n0.html?video=x9alt&foo=1">', ['x9alt']],
      ['https://www.dailymotion.com/video/x9nope', null],
    ],
  },
  {
    name: 'CE DM: master cdndirector auto del metadata',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'cdn/manifest/video/',
    samples: [
      ['"url":"https://geo-01.dailymotion.com/cdn/manifest/video/x9t6y8h.m3u8?sec=T&sec2=U"',
        ['https://geo-01.dailymotion.com/cdn/manifest/video/x9t6y8h.m3u8?sec=T&sec2=U']],
      ['https://geo-01.example.com/cdn/manifest/video/x9.m3u8', null],
    ],
  },
  {
    name: 'CE DM: qualities map (K:{"type","url"})',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'findAll(json).forEach',
    samples: [
      ['"1080":[{"type":"video/mp4","url":"https://s1.dmxx.io/mp4/x9_1080.mp4"}]',
        ['1080', 'video/mp4', 'https://s1.dmxx.io/mp4/x9_1080.mp4']],
    ],
  },
  {
    name: 'CE DM: bloque de subs es/en del metadata (urls)',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'Regex(""""(es[a-z-]*|en[a-z-]*',
    samples: [
      ['"es":{"completed":true,"nonloop":null,"urls":["https://s.mo/lang/es.m3u8"]}',
        ['es', 'https://s.mo/lang/es.m3u8']],
    ],
  },
  {
    name: 'CE gdriveplayer: clave XOR k="hex"',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'val key = Regex("k=',
    samples: [[';var k="deadc0deff04";', ['deadc0deff04']]],
  },
  {
    name: 'CE gdriveplayer: HLS="...m3u8" del script decodificado',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'val hlsRel = Regex("HLS=',
    samples: [
      [';HLS="hlsplaylist.php?s=a1&idhls=b2.m3u8";', ['hlsplaylist.php?s=a1&idhls=b2.m3u8']],
      [';HLS="h.m3u8";', ['h.m3u8']],
    ],
  },
  {
    name: 'CE Ok.ru: des-escape de \\uXXXX del data-options',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'u([0-9a-fA-F]{4})',
    samples: [
      [':\\u0026javascript:;', ['0026']],
      ['A\\u00e9B', ['00e9']],
      ['AéB', null],
    ],
  },
  {
    name: 'CE Ok.ru: hlsManifestUrl/ondemandHls del data-options decodificado',
    rel: 'common/src/CommonExtractors.kt',
    needle: 'ondemandHls)',
    samples: [
      [',"hlsManifestUrl":"https://vd.okcdn.ru/manifest.m3u8?sign=aB123",',
        ['https://vd.okcdn.ru/manifest.m3u8?sign=aB123']],
      [',"ondemandHls":"https://vd.okcdn.ru/ondemand.m3u8",', ['https://vd.okcdn.ru/ondemand.m3u8']],
      [',"progressive":"https://no.mp4",', null],
    ],
  },
  {
    name: 'SeriesDonghua: id de video DM (embed/video, dai.ly, player.html?video=)',
    rel: 'SeriesDonghuaProvider/src/main/kotlin/com/donghuaext/SeriesDonghuaProvider.kt',
    needle: 'dai\\\\.ly',
    samples: [
      ['https://www.dailymotion.com/embed/video/x8pxgja', ['x8pxgja']],
      ['https://dai.ly/video/x8pxgja', ['x8pxgja']],
      ['https://dai.ly/x8pxgja', null], // el embed corto dai.ly/VID no lo matchea (la web no lo usa)
      ['https://dailymotion.com/player/a1b2c3d.html?video=x7crhwl', ['x7crhwl']],
      ['https://vimeo.com/123', null],
    ],
  },
  {
    name: 'SeriesDonghua: embeds sueltos en HTML (DM/Ok/Rumble/Voe)',
    rel: 'SeriesDonghuaProvider/src/main/kotlin/com/donghuaext/SeriesDonghuaProvider.kt',
    needle: 'dailymotion\\\\.com/(?:embed/)?video|',
    samples: [
      ['<div data-embed="https://dailymotion.com/embed/video/x8pxgja"></div>',
        ['https://dailymotion.com/embed/video/x8pxgja']],
      ['  "https://ok.ru/videoembed/4416786401831"  ', ['https://ok.ru/videoembed/4416786401831']],
      ['src="https://rumble.com/embed/v7edwec/"', ['https://rumble.com/embed/v7edwec/']],
      ['https://voe.sx/e/9ix5v8rqz5h', ['https://voe.sx/e/9ix5v8rqz5h']],
    ],
  },
  {
    name: 'TioDonghua Ok.ru: hlsManifestUrl / ondemandHls del data-options',
    rel: 'TioDonghuaProvider/src/main/kotlin/com/donghuatiodonghua/TioDonghuaProvider.kt',
    needle: 'ondemandHls)',
    samples: [
      ['{"flashvars":{"metadata":{"hlsManifestUrl":"https://vd1.okcdn.ru/manifest.m3u8?sign=aB123"}}}',
        ['https://vd1.okcdn.ru/manifest.m3u8?sign=aB123']],
      ['{"ondemandHls":"https://vd2.okcdn.ru/ondemand.m3u8"}', ['https://vd2.okcdn.ru/ondemand.m3u8']],
    ],
  },
  {
    name: 'TioDonghua Rumble: mapa ua de MP4 por calidad del embedJS',
    rel: 'TioDonghuaProvider/src/main/kotlin/com/donghuatiodonghua/TioDonghuaProvider.kt',
    needle: 'rumble\\\\.cloud',
    samples: [
      ['"360":["https://8xm.rumble.cloud/video/abc_360p.mp4"],', ['360', 'https://8xm.rumble.cloud/video/abc_360p.mp4']],
      ['"1080":["https://9am-1.rumble.cloud/video/xyz_1080p.mp4"]', ['1080', 'https://9am-1.rumble.cloud/video/xyz_1080p.mp4']],
    ],
  },
  {
    name: 'SeriesDonghua Rumble: master hls-vod del bloque m.f[] del embed',
    rel: 'SeriesDonghuaProvider/src/main/kotlin/com/donghuaext/SeriesDonghuaProvider.kt',
    needle: 'rumble[.]com/hls-vod',
    samples: [
      ['"hls":{"url":"https://rumble.com/hls-vod/7de15c9/P2700_P1440_api.m3u8"}',
        ['https://rumble.com/hls-vod/7de15c9/P2700_P1440_api.m3u8']],
    ],
  },
];

// ==== SYNC: resolver todas las literales por ancla =====================
const resolved = [];
let drifts = 0;
for (const t of TESTS) {
  const r = litNear(t.rel, t.needle);
  if (r.drift) {
    console.log('DRIFT FAIL :: ' + t.name);
    console.log('   ' + r.drift);
    drifts += 1;
  } else {
    resolved.push({ name: t.name, lit: r.lit, samples: t.samples });
  }
}

// ==== BEHAVIOR: programa Java ==========================================
function b64(s) { return Buffer.from(s, 'utf8').toString('base64'); }
function arr(ls) { return ls.join('\n') + '\n'; }

const j = [];
j.push('import java.util.regex.*;');
j.push('import java.util.Base64;');
j.push('import java.nio.charset.StandardCharsets;');
j.push('public class RtBhv {');
j.push(' static String dec(String b){ return new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8); }');
j.push(' static String printable(String s){ return s.replaceAll("[^\\\\x20-\\\\x7e]", "?"); }');
j.push(' static String caps(Matcher m){');
j.push('  StringBuilder sb = new StringBuilder();');
j.push('  if (m.group(1) != null) sb.append(m.group(1)); else sb.append("<g1=null>");');
j.push('  for (int g = 2; g <= m.groupCount(); g++) {');
j.push('   sb.append(" | ");');
j.push('   if (m.group(g) != null) sb.append(m.group(g)); else sb.append("<g' + '"+g+"' + '=null>");');
j.push('  }');
j.push('  return sb.toString();');
j.push(' }');
j.push(' static int run(String name, String pat, String[] in_, String[] want_) {');
j.push('  int failures = 0;');
j.push('  try {');
j.push('   Pattern P = Pattern.compile(pat);');
j.push('   for (int s = 0; s < in_.length; s++) {');
j.push('    Matcher m = P.matcher(in_[s]);');
j.push('    String got;');
j.push('    if (!m.find()) got = "NOMATCH";');
j.push('    else if (m.groupCount() == 0) got = m.group();');
j.push('    else got = caps(m);');
j.push('    if (!got.equals(want_[s])) {');
j.push('     System.out.println("BHV FAIL :: " + name + " [muestra " + s + "]");');
j.push('     System.out.println("   entrada : " + printable(in_[s]));');
j.push('     System.out.println("   got     : " + got);');
j.push('     System.out.println("   esperado: " + want_[s]);');
j.push('     failures++;');
j.push('    }');
j.push('   }');
j.push('  } catch (Throwable e) {');
j.push('   String msg = (e.getMessage() == null) ? e.toString() : e.getMessage();');
j.push('   System.out.println("BHV FAIL :: " + name + " [patron] " + msg.replace("\\n", " "));');
j.push('   failures++;');
j.push('  }');
j.push('  return failures;');
j.push(' }');
j.push(' public static void main(String[] args) {');
j.push('  int failures = 0;');
for (const t of resolved) {
  const name = t.name.replace(/\\/g, '').replace(/"/g, "'");
  const ins = t.samples.map(s => s[0]);
  const wts = t.samples.map(s => (s[1] === null ? 'NOMATCH' : s[1].join(' | ')));
  const inArr = 'new String[]{' + ins.map(x => 'dec("' + b64(x) + '")').join(',') + '}';
  const wtArr = 'new String[]{' + wts.map(x => 'dec("' + b64(x) + '")').join(',') + '}';
  j.push('  failures += run("' + name + '", dec("' + b64(t.lit) + '"), ' + inArr + ', ' + wtArr + ');');
}
j.push('  if (failures == 0) {');
j.push('   System.out.println("TODOS OK");');
j.push('  } else {');
j.push('   System.out.println("FALLEN " + failures + " assertion(es)");');
j.push('  }');
j.push(' }');
j.push('}');
const javaSrc = arr(j);

const dir = mkdtempSync(path.join(tmpdir(), 'rgx-'));
writeFileSync(path.join(dir, 'RtBhv.java'), javaSrc);
let out = '';
try {
  execSync('javac -encoding UTF-8 -d "' + dir + '" "' + path.join(dir, 'RtBhv.java') + '"', { stdio: 'pipe' });
  out = execSync('java -cp "' + dir + '" RtBhv', { stdio: 'pipe', encoding: 'utf8' });
  process.stdout.write(out);
} catch (e) {
  console.error('BHV FAIL :: compilando el programa de tests (fallo de javac/java):');
  console.error(((e.stderr || e.stdout || '') + '').split('\n').slice(0, 20).join('\n'));
  process.exit(1);
}

const failLine = out.split('\n').find(l => l.startsWith('TODOS OK') || l.startsWith('FALLEN'));
const failed = drifts > 0 || !failLine || failLine.startsWith('FALLEN');
if (drifts > 0) console.log('DRIFT: ' + drifts + ' caso(s) con ancla rota');
process.exit(failed ? 1 : 0);
