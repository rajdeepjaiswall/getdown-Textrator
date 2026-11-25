// Vercel build step: copies the static site to dist/ and writes config.js from env vars,
// so keys live in Vercel settings, not in git.
const fs = require('fs');
const path = require('path');
const src = __dirname, out = path.join(__dirname, 'dist');
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
for (const f of ['index.html', 'privacy.html', 'styles.css', 'logo.png', 'favicon.png', 'getdown-foundation-logo.png']) fs.copyFileSync(path.join(src, f), path.join(out, f));
fs.mkdirSync(path.join(out, 'admin'), { recursive: true });
fs.copyFileSync(path.join(src, 'admin', 'index.html'), path.join(out, 'admin', 'index.html'));
const cfg = {
  supabaseUrl: process.env.SUPABASE_URL || '',
  supabaseAnonKey: process.env.SUPABASE_ANON_KEY || '',
  apkUrl: process.env.APK_URL || 'https://github.com/rajdeepjaiswall/getdown-Textrator/releases/latest/download/GetDownTextrator.apk',
};
fs.writeFileSync(path.join(out, 'config.js'), `window.TT_CONFIG = ${JSON.stringify(cfg, null, 2)};\n`);
console.log('Built dist/ (backend ' + (cfg.supabaseUrl ? 'connected' : 'not configured') + ')');
