// Evalúa docx-preview: renderiza cada .docx de docs/ en un Chromium del tamaño de un celular,
// mide el tiempo (con la CPU frenada 4x para parecerse a un teléfono) y guarda capturas.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const nm = (...p) => path.join(__dirname, 'node_modules', ...p);
  const libPath = fs.existsSync(nm('docx-preview', 'dist', 'docx-preview.min.js'))
    ? nm('docx-preview', 'dist', 'docx-preview.min.js') : nm('docx-preview', 'dist', 'docx-preview.js');
  const zipPath = nm('jszip', 'dist', 'jszip.min.js');
  const lib = fs.readFileSync(libPath, 'utf8');
  const jszip = fs.readFileSync(zipPath, 'utf8');
  const version = JSON.parse(fs.readFileSync(nm('docx-preview', 'package.json'), 'utf8')).version;
  const out = 'resultados';
  fs.mkdirSync(out, { recursive: true });
  const browser = await chromium.launch();
  const report = [];

  for (const f of fs.readdirSync('docs').sort()) {
    const b64 = fs.readFileSync(path.join('docs', f)).toString('base64');
    const page = await browser.newPage({ viewport: { width: 412, height: 860 }, deviceScaleFactor: 1.5 });
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Emulation.setCPUThrottlingRate', { rate: 4 });
    await page.setContent('<html><head><meta name="viewport" content="width=device-width, initial-scale=1">' +
      '<style>body{margin:0;background:#e4e4e4}</style></head><body><div id="c"></div></body></html>');
    await page.addScriptTag({ content: jszip });
    await page.addScriptTag({ content: lib });
    let info;
    try {
      info = await page.evaluate(async (b64) => {
        const bin = atob(b64);
        const u = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
        const t0 = performance.now();
        await docx.renderAsync(new Blob([u]), document.getElementById('c'), null, {
          inWrapper: true, breakPages: true, renderHeaders: true, renderFooters: true,
          renderFootnotes: true, renderEndnotes: true, useBase64URL: true,
        });
        const t1 = performance.now();
        const w = document.querySelector('.docx-wrapper');
        return { ms: Math.round(t1 - t0), anchoPagina: w ? w.scrollWidth : 0,
                 paginas: document.querySelectorAll('section.docx').length, error: null };
      }, b64);
    } catch (e) {
      info = { error: String(e) };
    }
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${out}/${f}-1-tal-cual.png` });
    // Vista ajustada: la página entera escalada al ancho del celular
    await page.evaluate(() => {
      const w = document.querySelector('.docx-wrapper');
      if (w) document.body.style.zoom = (window.innerWidth / w.scrollWidth).toFixed(3);
    });
    await page.waitForTimeout(300);
    const big = (info.paginas || 0) > 6;
    await page.screenshot({ path: `${out}/${f}-2-ajustada.png`, fullPage: !big });
    report.push({ archivo: f, ...info });
    await page.close();
  }

  const size = (p) => fs.statSync(p).size;
  fs.writeFileSync(`${out}/reporte.json`, JSON.stringify({
    version, cpuFrenada: '4x', tamanoBytes: {
      [path.basename(libPath)]: size(libPath),
      'jszip.min.js': size(zipPath),
    }, resultados: report,
  }, null, 2));
  await browser.close();
})();
