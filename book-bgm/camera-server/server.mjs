// 책 배경음악 카메라 중계 서버
//
// 책을 찍는 XIAO ESP32-S3 보드와 백엔드(backend/camera_book.py) 사이에서
// "가장 최근 사진 한 장"을 들고 있는 서버. 백엔드는 이 서버만 알면 된다.
//
//   백엔드  POST /capture?cmd=1   "지금 찍어"
//   백엔드  GET  /photo/image     가장 최근 사진(JPEG)
//   보드    POST /photo           사진 올리기 (보내는 방식 펌웨어, multipart "imageFile")
//   사람    GET  /photo           미리보기 페이지
//
// 보드 펌웨어는 두 가지를 모두 받는다.
//   1) 가져오는 방식: firmware/CameraWebServer — 이 서버가 보드의 /capture 를 불러 사진을 가져온다.
//      BOARD_URL 에 보드 주소를 넣는다.
//   2) 보내는 방식: 보드가 0.8초마다 POST /photo 로 사진을 올린다. BOARD_URL 을 비워도 된다.
//
// 외부 라이브러리 없이 Node 18 이상에서 돈다:  node server.mjs

import http from "node:http";

const PORT = Number(process.env.PORT || 4000);
const BOARD_URL = (process.env.BOARD_URL ?? "http://192.168.0.25").replace(/\/+$/, "");
// esp32-camera 의 framesize_t 번호. Arduino-ESP32 3.3.x 에서 15 = UXGA(1600x1200).
// 코어 버전에 따라 번호가 달라질 수 있어, 실제로 받은 사진 크기를 BOARD_WIDTH 와 비교해 확인한다.
const BOARD_FRAMESIZE = process.env.BOARD_FRAMESIZE || "15";
const BOARD_WIDTH = Number(process.env.BOARD_WIDTH || 1600);
const BOARD_QUALITY = process.env.BOARD_QUALITY || "10"; // 낮을수록 고화질(0~63)
// XIAO ESP32-S3 Sense 의 카메라는 기본 설정에서 좌우가 뒤집힌(거울) 사진을 낸다.
// 거울 사진의 글자는 AI 가 읽지 못하고 엉뚱한 책으로 인식하므로 보드에서 바로잡는다.
const BOARD_HMIRROR = process.env.BOARD_HMIRROR || "1";
const BOARD_VFLIP = process.env.BOARD_VFLIP || "0";

const FRESH_MS = 1500; // 이보다 오래된 사진이면 보드에서 새로 가져온다
// 이보다 오래된 사진은 내주지 않는다. 보드가 멈췄을 때 옛 사진으로 엉뚱한 인식이 나오는 것을 막는다.
const STALE_MS = 10000;
// 백엔드는 촬영 신호를 4초만 기다린다. 보드가 꺼져 있을 때 그 안에 오류를 돌려주도록 짧게 잡는다.
const BOARD_TIMEOUT_MS = 3000;
const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

let latest = null; // { jpeg, at, source, width, height }
let lastError = null;
let pulling = null; // 진행 중인 가져오기 (동시 요청은 하나로 합친다)
let boardConfigured = false;
let lastPushAt = 0; // 보드가 마지막으로 사진을 올린 시각

// 보드가 스스로 사진을 올리고 있으면(보내는 방식 펌웨어) 가져오기를 시도하지 않는다.
const shouldPull = () => Boolean(BOARD_URL) && Date.now() - lastPushAt > STALE_MS;

// JPEG 의 SOF 마커에서 가로·세로를 읽는다. JPEG 이 아니면 null.
function jpegSize(buf) {
  if (buf.length < 4 || buf[0] !== 0xff || buf[1] !== 0xd8) return null;
  let i = 2;
  while (i + 9 < buf.length) {
    if (buf[i] !== 0xff) return null;
    const marker = buf[i + 1];
    if (marker === 0xff) {
      i += 1;
      continue;
    }
    const isSof = marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker);
    if (isSof) return { height: buf.readUInt16BE(i + 5), width: buf.readUInt16BE(i + 7) };
    i += 2 + buf.readUInt16BE(i + 2);
  }
  return null;
}

function store(jpeg, source) {
  const size = jpegSize(jpeg);
  if (!size) throw new Error("JPEG 이 아닌 데이터를 받았어요");
  latest = { jpeg, at: Date.now(), source, ...size };
  lastError = null;
  return latest;
}

async function boardGet(path) {
  let res;
  try {
    res = await fetch(`${BOARD_URL}${path}`, { signal: AbortSignal.timeout(BOARD_TIMEOUT_MS) });
  } catch {
    throw new Error(`보드(${BOARD_URL})가 응답하지 않아요. 전원과 와이파이, 주소를 확인하세요`);
  }
  if (!res.ok) throw new Error(`보드 응답 ${res.status} (${path})`);
  return Buffer.from(await res.arrayBuffer());
}

// CameraWebServer 는 켜질 때 320x240 으로 시작한다. 책 글자를 읽으려면 해상도를 올려야 한다.
async function configureBoard() {
  await boardGet(`/control?var=framesize&val=${BOARD_FRAMESIZE}`);
  await boardGet(`/control?var=quality&val=${BOARD_QUALITY}`);
  await boardGet(`/control?var=hmirror&val=${BOARD_HMIRROR}`);
  await boardGet(`/control?var=vflip&val=${BOARD_VFLIP}`);
  // 설정을 바꾼 직후의 프레임은 이전 해상도·노출이 섞여 있어 한 장 버린다.
  await boardGet("/capture");
  boardConfigured = true;
}

async function pullOnce() {
  if (!boardConfigured) await configureBoard();
  let photo = store(await boardGet("/capture"), "pull");
  // 보드가 재시작되면 해상도가 320x240 으로 돌아간다. 다시 맞추고 한 번 더 찍는다.
  if (photo.width !== BOARD_WIDTH) {
    await configureBoard();
    photo = store(await boardGet("/capture"), "pull");
    if (photo.width !== BOARD_WIDTH) {
      console.warn(
        `해상도가 ${photo.width}x${photo.height} 입니다 (기대 가로 ${BOARD_WIDTH}). ` +
          `BOARD_FRAMESIZE=${BOARD_FRAMESIZE} 가 이 보드의 코어 버전과 맞는지 확인하세요.`,
      );
    }
  }
  return photo;
}

function pull() {
  if (!BOARD_URL) return Promise.reject(new Error("BOARD_URL 이 비어 있어요"));
  pulling ??= pullOnce()
    .catch((err) => {
      boardConfigured = false;
      lastError = `${new Date().toLocaleTimeString("ko-KR")} ${err.message}`;
      throw err;
    })
    .finally(() => {
      pulling = null;
    });
  return pulling;
}

async function freshPhoto() {
  if (latest && Date.now() - latest.at < FRESH_MS) return latest;
  if (shouldPull()) {
    try {
      return await pull();
    } catch {
      // 보드가 잠깐 안 받으면 아래에서 마지막 사진을 준다
    }
  }
  if (latest && Date.now() - latest.at < STALE_MS) return latest;
  if (latest && !shouldPull()) lastError = "보드가 사진을 보내지 않고 있어요. 전원과 와이파이를 확인하세요";
  return null;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on("data", (chunk) => {
      total += chunk.length;
      if (total > MAX_UPLOAD_BYTES) {
        reject(new Error("사진이 너무 커요"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

// multipart/form-data 본문에서 첫 번째 파일의 내용만 꺼낸다.
function firstFilePart(body, contentType) {
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(contentType || "");
  if (!boundary) return body; // multipart 가 아니면 본문 전체가 사진
  const delimiter = Buffer.from(`--${boundary[1] || boundary[2]}`);
  const start = body.indexOf(delimiter);
  const headerEnd = body.indexOf("\r\n\r\n", start);
  if (start < 0 || headerEnd < 0) return null;
  const dataStart = headerEnd + 4;
  const dataEnd = body.indexOf(Buffer.concat([Buffer.from("\r\n"), delimiter]), dataStart);
  return dataEnd < 0 ? null : body.subarray(dataStart, dataEnd);
}

function sendJson(res, status, data) {
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
  res.end(JSON.stringify(data));
}

function statusInfo() {
  return {
    board_url: BOARD_URL || null,
    mode: shouldPull() ? "가져오는 방식" : "보내는 방식",
    has_photo: Boolean(latest),
    age_ms: latest ? Date.now() - latest.at : null,
    width: latest?.width ?? null,
    height: latest?.height ?? null,
    bytes: latest?.jpeg.length ?? null,
    source: latest?.source ?? null,
    last_error: lastError,
  };
}

const PREVIEW_PAGE = `<!doctype html>
<html lang="ko">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>카메라 중계 서버</title>
<style>
  body { margin: 0; padding: 16px; font-family: -apple-system, "Apple SD Gothic Neo", sans-serif; background: #111; color: #eee; }
  h1 { font-size: 18px; margin: 0 0 8px; }
  #info { font-size: 14px; color: #aaa; margin-bottom: 12px; }
  #error { color: #ff8a80; }
  img { max-width: 100%; border-radius: 8px; background: #222; min-height: 120px; }
</style>
<h1>카메라 중계 서버</h1>
<div id="info">사진을 기다리는 중…</div>
<img id="photo" alt="가장 최근 사진">
<script>
  const info = document.getElementById("info");
  const photo = document.getElementById("photo");
  async function refresh() {
    try {
      const res = await fetch("/photo/image?t=" + Date.now());
      if (res.ok) {
        const old = photo.src;
        photo.src = URL.createObjectURL(await res.blob());
        if (old) URL.revokeObjectURL(old);
      }
      const s = await (await fetch("/status")).json();
      info.innerHTML = s.has_photo
        ? s.mode + " · " + s.width + "×" + s.height + " · " + Math.round(s.bytes / 1024) + "KB" +
          (s.last_error ? ' · <span id="error">' + s.last_error + "</span>" : "")
        : '아직 사진이 없어요' + (s.last_error ? ' · <span id="error">' + s.last_error + "</span>" : "");
    } catch (err) {
      info.textContent = "중계 서버에 연결할 수 없어요";
    }
    setTimeout(refresh, 2000);
  }
  refresh();
</script>
</html>`;

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, "http://localhost");
  res.setHeader("Access-Control-Allow-Origin", "*");
  try {
    if (req.method === "GET" && (url.pathname === "/" || url.pathname === "/photo")) {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      res.end(PREVIEW_PAGE);
    } else if (req.method === "GET" && url.pathname === "/photo/image") {
      const photo = await freshPhoto();
      if (!photo) {
        sendJson(res, 503, { error: lastError || "아직 사진이 없어요" });
        return;
      }
      res.writeHead(200, {
        "Content-Type": "image/jpeg",
        "Content-Length": photo.jpeg.length,
        "Cache-Control": "no-store",
      });
      res.end(photo.jpeg);
    } else if (req.method === "POST" && url.pathname === "/capture") {
      // 보내는 방식 보드는 알아서 계속 올리므로 할 일이 없다.
      if (shouldPull()) await pull();
      sendJson(res, 200, { ok: true, ...statusInfo() });
    } else if (req.method === "POST" && url.pathname === "/photo") {
      const jpeg = firstFilePart(await readBody(req), req.headers["content-type"]);
      if (!jpeg || !jpegSize(jpeg)) {
        sendJson(res, 400, { error: "JPEG 사진을 찾지 못했어요" });
        return;
      }
      store(Buffer.from(jpeg), "push");
      lastPushAt = Date.now();
      sendJson(res, 200, { ok: true });
    } else if (req.method === "GET" && url.pathname === "/status") {
      sendJson(res, 200, statusInfo());
    } else {
      sendJson(res, 404, { error: "없는 주소예요" });
    }
  } catch (err) {
    if (!res.headersSent) sendJson(res, 502, { error: err.message });
    else res.end();
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`카메라 중계 서버: http://localhost:${PORT}/photo`);
  console.log(BOARD_URL ? `보드에서 가져오기: ${BOARD_URL}` : "보드가 POST /photo 로 올리기를 기다리는 중");
});
