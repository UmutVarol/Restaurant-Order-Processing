# The promo video

A 30-second animated introduction to Sofra for LinkedIn and Instagram. The plan, storyboard and post texts are in
[`../VIDEO_PLAN.md`](../VIDEO_PLAN.md) (Turkish).

| File | What it is |
|---|---|
| `final/sofra-tr-9x16.mp4` | 1080×1920: Instagram Reels and Stories, LinkedIn vertical video |
| `final/sofra-tr-4x5.mp4` | 1080×1350: LinkedIn and Instagram feeds (the same frames, cropped) |
| `final/sofra-cover-*.png` | Cover images (the logo scene) |
| `video.html` | The film: one page whose every frame is a function of time |
| `shots/tr/` | Real screens of the app, captured by `capture.js` |
| `capture.js` | Plays one evening at one table in a running Sofra and screenshots it |
| `render.js` | Asks the page for each frame and encodes them with ffmpeg |
| `make-qr.mjs`, `qr-data.js` | The QR code of the project's address, made with the app's own QR library |

## Watching and editing

Open `video.html` in a browser: it plays in real time, scaled to the window. Space pauses, ← and → step one frame,
`?t=12.5` opens on a moment. Texts are in the `TEXT` table at the top of the script, timings in `T`, and each scene
is a few lines of `tw(element, start, duration, { opacity, x, y, scale })`.

Everything that matters is kept between y=250 and y=1480 of the 1080×1920 stage, the part the Instagram Reels
interface leaves uncovered; the 4:5 version is the crop y=190…1540 of the same frames.

## Rendering

Needs Node with Playwright (the `e2e/` folder has it) and `ffmpeg` with libx264.

```bash
cd e2e
NODE_PATH=$PWD/node_modules node ../marketing/video/render.js ../marketing/video/final/sofra-tr        # both formats, ~2 min
NODE_PATH=$PWD/node_modules node ../marketing/video/render.js --stills /tmp/stills 7.2,14.9,29.8    # single frames
```

## Capturing the screens again

After the app's look changes, or for another language:

```bash
./mvnw -q package -DskipTests && java -jar target/restaurant-order-processing.jar &   # demo data, on :8080
cd e2e && NODE_PATH=$PWD/node_modules node ../marketing/video/capture.js ../marketing/video/shots/tr tr
```

The script seats a free table, switches the restaurant to QR-first for the capture (and back), describes the demo
dishes in Turkish for the Turkish video (and restores them), sets the screens' clocks to the evening, and settles
the table's bill at the end. It prefers table 7, then 4; the film's texts say "Masa 4" (the card on the table and the
waiter's toast in `TEXT`), so change those if another table was used. English texts are in `TEXT.en`, but the
English screens have not been captured or reviewed yet.

The project's address in the QR code and the closing card: `node make-qr.mjs <url>`, and `outro.url` in `TEXT`.
