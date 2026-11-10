// Renders video.html to MP4, frame by frame: the page is asked for each instant (window.seek), screenshotted, and
// the frames are piped into ffmpeg. Nothing is recorded in real time, so the result is exact and repeatable.
//
//   node render.js <out base> [lang]               <base>-9x16.mp4 (1080×1920, Reels, Stories, LinkedIn vertical) and
//                                                  <base>-4x5.mp4 (1080×1350, feeds): the same frames, cropped to
//                                                  y=190…1540, where the film keeps everything that matters
//   node render.js --stills <dir> 1.2,4.5 [lang]   single frames, to check a scene
//
// 30 fps, H.264 high profile, BT.709, a silent AAC track (some uploaders refuse a file with no audio stream).
// Run from e2e/ (which has Playwright):  NODE_PATH=$PWD/node_modules node ../marketing/video/render.js out/sofra-tr
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const FPS = 30;
const WIDTH = 1080;
const HEIGHT = 1920;

(async () => {
  const args = process.argv.slice(2);
  const stills = args[0] === '--stills';
  const lang = (stills ? args[3] : args[1]) || 'tr';
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: WIDTH, height: HEIGHT }, deviceScaleFactor: 1 });
  page.on('pageerror', (error) => { console.error('page error:', error.message); process.exitCode = 1; });
  await page.goto(`file://${path.join(__dirname, 'video.html')}?render=1&lang=${lang}`);
  await page.evaluate(() => window.ready);
  const duration = await page.evaluate(() => window.DURATION);

  if (stills) {
    fs.mkdirSync(args[1], { recursive: true });
    for (const t of args[2].split(',').map(Number)) {
      await page.evaluate((at) => window.seek(at), t);
      await page.screenshot({ path: path.join(args[1], `t${t.toFixed(2).padStart(5, '0')}.png`) });
    }
    await browser.close();
    return;
  }

  const base = path.resolve(args[0]);
  fs.mkdirSync(path.dirname(base), { recursive: true });
  const frames = Math.round(duration * FPS);
  const encode = ['-c:v', 'libx264', '-preset', 'slow', '-crf', '14', '-maxrate', '12M', '-bufsize', '24M', '-pix_fmt', 'yuv420p', '-profile:v', 'high', '-level', '4.2',
    '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-color_range', 'tv',
    '-c:a', 'aac', '-b:a', '128k', '-t', String(duration), '-movflags', '+faststart'];
  const ffmpeg = spawn('ffmpeg', ['-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-f', 'lavfi', '-i', 'anullsrc=channel_layout=stereo:sample_rate=48000',
    '-filter_complex', '[0:v]scale=in_range=full:out_range=tv:out_color_matrix=bt709,format=yuv420p,split=2[reel][full];[full]crop=1080:1350:0:190[feed]',
    '-map', '[reel]', '-map', '1:a', ...encode, `${base}-9x16.mp4`,
    '-map', '[feed]', '-map', '1:a', ...encode, `${base}-4x5.mp4`], { stdio: ['pipe', 'inherit', 'inherit'] });
  const done = new Promise((resolve, reject) => ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited with ${code}`)))));
  const cdp = await page.context().newCDPSession(page);
  const started = Date.now();
  for (let frame = 0; frame < frames; frame++) {
    await page.evaluate((at) => window.seek(at), frame / FPS);
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', optimizeForSpeed: true });
    if (!ffmpeg.stdin.write(Buffer.from(data, 'base64'))) await new Promise((resolve) => ffmpeg.stdin.once('drain', resolve));
    if (frame % 90 === 0) console.log(`frame ${frame}/${frames}  ${((Date.now() - started) / 1000).toFixed(0)}s`);
  }
  ffmpeg.stdin.end();
  await done;
  await browser.close();
  console.log(`wrote ${base}-9x16.mp4 and ${base}-4x5.mp4: ${frames} frames, ${duration}s`);
})();
