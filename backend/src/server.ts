import express, { Request, Response } from "express";
import cors from "cors";
import rateLimit from "express-rate-limit";
import { spawn, ChildProcess } from "child_process";
import { v4 as uuidv4 } from "uuid";
import * as fs from "fs";
import * as path from "path";
import dotenv from "dotenv";

dotenv.config();

const app = express();
app.use(cors());
app.use(express.json());

const PORT = Number(process.env.PORT || 8080);
const TEMP_DIR = process.env.TEMP_DIR || "/tmp/dragon-downloader";
const JOB_TTL_MS = Number(process.env.JOB_TTL_MS || 30 * 60 * 1000);
const YTDLP_BIN = process.env.YTDLP_PATH || "yt-dlp";
const FFMPEG_BIN = process.env.FFMPEG_PATH || "ffmpeg";
const FFPROBE_BIN = process.env.FFPROBE_PATH || "ffprobe";

if (!fs.existsSync(TEMP_DIR)) {
  fs.mkdirSync(TEMP_DIR, { recursive: true });
}

const limiter = rateLimit({
  windowMs: Number(process.env.RATE_LIMIT_WINDOW_MS || 60_000),
  max: Number(process.env.MAX_REQUESTS_PER_WINDOW || 60),
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: "Too many requests, please try again shortly." },
});
app.use("/api/", limiter);

interface DownloadJob {
  jobId: string;
  url: string;
  formatId: string;
  container: string;
  isAudioOnly: boolean;
  status: "downloading" | "merging" | "converting" | "validating" | "completed" | "failed" | "cancelled";
  progress: number;
  speedBytesPerSec: number;
  downloadedBytes: number;
  totalBytes: number;
  outputFilePath?: string;
  filename?: string;
  mimeType?: string;
  error?: string;
  createdAt: number;
  process?: ChildProcess;
}

const jobs = new Map<string, DownloadJob>();

// Periodic cleanup of expired jobs and temporary files
setInterval(() => {
  const now = Date.now();
  for (const [jobId, job] of jobs.entries()) {
    if (now - job.createdAt > JOB_TTL_MS) {
      cleanupJobFiles(jobId);
      jobs.delete(jobId);
    }
  }
}, 5 * 60 * 1000);

function cleanupJobFiles(jobId: string) {
  const jobDir = path.join(TEMP_DIR, jobId);
  if (fs.existsSync(jobDir)) {
    fs.rmSync(jobDir, { recursive: true, force: true });
  }
}

function validateUrlInput(rawUrl: unknown): string {
  if (typeof rawUrl !== "string" || !rawUrl.trim()) {
    throw new Error("URL is required.");
  }
  const parsed = new URL(rawUrl.trim());
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new Error("Only http and https URLs are supported.");
  }
  return parsed.toString();
}

function runCommandJson(bin: string, args: string[]): Promise<any> {
  return new Promise((resolve, reject) => {
    const proc = spawn(bin, args);
    let stdout = "";
    let stderr = "";
    proc.stdout.on("data", (d) => (stdout += d.toString()));
    proc.stderr.on("data", (d) => (stderr += d.toString()));
    proc.on("close", (code) => {
      if (code !== 0) {
        return reject(new Error(stderr.trim() || `${bin} exited with code ${code}`));
      }
      try {
        resolve(JSON.parse(stdout));
      } catch (err) {
        reject(new Error(`Failed to parse ${bin} JSON output`));
      }
    });
  });
}

function verifyWithFfprobe(filePath: string, expectAudioOnly: boolean): Promise<void> {
  return new Promise((resolve, reject) => {
    if (!fs.existsSync(filePath) || fs.statSync(filePath).size <= 0) {
      return reject(new Error("Output file does not exist or is 0 bytes."));
    }
    const args = [
      "-v", "error",
      "-show_entries", "format=duration,size,format_name:stream=codec_type,codec_name,width,height",
      "-of", "json",
      filePath,
    ];
    runCommandJson(FFPROBE_BIN, args)
      .then((probe) => {
        const streams: any[] = probe.streams || [];
        const hasVideo = streams.some((s) => s.codec_type === "video");
        const hasAudio = streams.some((s) => s.codec_type === "audio");
        if (!expectAudioOnly && !hasVideo) {
          return reject(new Error("FFprobe verification failed: missing video stream."));
        }
        if (expectAudioOnly && !hasAudio) {
          return reject(new Error("FFprobe verification failed: missing audio stream."));
        }
        resolve();
      })
      .catch((err) => reject(err));
  });
}

// 1. POST /api/media/info
app.post("/api/media/info", async (req: Request, res: Response) => {
  try {
    const validUrl = validateUrlInput(req.body?.url);
    const meta = await runCommandJson(YTDLP_BIN, [
      "--dump-single-json",
      "--no-Playlist",
      "--no-warnings",
      validUrl,
    ]);

    const duration = Number(meta.duration || 0);
    const rawFormats: any[] = meta.formats || [];
    const videoFormats: any[] = [];
    const audioFormats: any[] = [];

    for (const f of rawFormats) {
      if (!f.url) continue;
      const vcodec = f.vcodec && f.vcodec !== "none" ? f.vcodec : null;
      const acodec = f.acodec && f.acodec !== "none" ? f.acodec : null;
      const filesize = Number(f.filesize || f.filesize_approx || -1);
      const tbr = Math.round(Number(f.tbr || f.vbr || f.abr || 0));

      if (vcodec) {
        const height = Number(f.height || 0);
        if (height <= 0) continue;
        videoFormats.push({
          id: String(f.format_id),
          qualityLabel: `${height}p${f.fps && f.fps > 30 ? f.fps : ""} (${(f.ext || "mp4").toUpperCase()})`,
          resolutionHeight: height,
          width: Number(f.width || 0),
          fps: Number(f.fps || 30),
          container: "mp4",
          mimeType: "video/mp4",
          videoCodec: vcodec,
          audioCodec: acodec || "aac",
          bitrateKbps: tbr,
          bitrateLabel: tbr > 0 ? `${tbr} kbps` : "",
          contentLengthBytes: filesize,
          isEstimatedSize: !f.filesize,
          isAudioOnly: false,
          needsMerging: !acodec,
        });
      } else if (acodec) {
        const abr = Math.round(Number(f.abr || tbr || 128));
        audioFormats.push({
          id: String(f.format_id),
          qualityLabel: `${(f.ext || "m4a").toUpperCase()} Audio (${abr} kbps)`,
          container: f.ext === "mp3" ? "mp3" : "m4a",
          mimeType: f.ext === "mp3" ? "audio/mpeg" : "audio/mp4",
          audioCodec: acodec,
          bitrateKbps: abr,
          bitrateLabel: `${abr} kbps`,
          contentLengthBytes: filesize,
          isEstimatedSize: !f.filesize,
          isAudioOnly: true,
        });
      }
    }

    res.json({
      title: meta.title || "Media Stream",
      creator: meta.uploader || meta.channel || "Creator",
      durationSeconds: duration,
      thumbnailUrl: meta.thumbnail || "",
      platform: (meta.extractor_key || "OTHER").toUpperCase(),
      canonicalUrl: meta.webpage_url || validUrl,
      videoFormats,
      audioFormats,
    });
  } catch (err: any) {
    res.status(400).json({ error: err.message || "Failed to extract media metadata." });
  }
});

// 2. POST /api/media/download
app.post("/api/media/download", async (req: Request, res: Response) => {
  try {
    const validUrl = validateUrlInput(req.body?.url);
    const formatId = String(req.body?.formatId || "best");
    const isAudioOnly = Boolean(req.body?.isAudioOnly);
    const container = String(req.body?.container || (isAudioOnly ? "m4a" : "mp4"));

    const jobId = uuidv4();
    const jobDir = path.join(TEMP_DIR, jobId);
    fs.mkdirSync(jobDir, { recursive: true });

    const outPath = path.join(jobDir, `output.${container}`);
    const job: DownloadJob = {
      jobId,
      url: validUrl,
      formatId,
      container,
      isAudioOnly,
      status: "downloading",
      progress: 0,
      speedBytesPerSec: 0,
      downloadedBytes: 0,
      totalBytes: -1,
      outputFilePath: outPath,
      filename: `dragon_media_${jobId.slice(0, 8)}.${container}`,
      mimeType: isAudioOnly ? `audio/${container}` : `video/${container}`,
      createdAt: Date.now(),
    };
    jobs.set(jobId, job);

    const ytdlpArgs = isAudioOnly
      ? ["-x", "--audio-format", container, "--newline", "-o", outPath, validUrl]
      : ["-f", `${formatId}+bestaudio[ext=m4a]/best[ext=mp4]/best`, "--merge-output-format", "mp4", "--newline", "-o", outPath, validUrl];

    const proc = spawn(YTDLP_BIN, ytdlpArgs);
    job.process = proc;

    proc.stdout.on("data", (chunk) => {
      const line = chunk.toString();
      if (line.includes("[Merger]") || line.includes("Merging formats")) {
        job.status = "merging";
      }
      const pctMatch = line.match(/(\d+(?:\.\d+)?)%/);
      if (pctMatch) {
        job.progress = Math.min(0.95, parseFloat(pctMatch[1]) / 100);
      }
    });

    proc.on("close", async (code) => {
      if (job.status === "cancelled") return;
      if (code !== 0) {
        job.status = "failed";
        job.error = `Extraction process exited with code ${code}`;
        return;
      }
      try {
        job.status = "validating";
        await verifyWithFfprobe(outPath, isAudioOnly);
        const stat = fs.statSync(outPath);
        job.totalBytes = stat.size;
        job.downloadedBytes = stat.size;
        job.progress = 1.0;
        job.status = "completed";
      } catch (verifyErr: any) {
        job.status = "failed";
        job.error = verifyErr.message || "Output validation failed.";
      }
    });

    res.json({ jobId, status: job.status });
  } catch (err: any) {
    res.status(400).json({ error: err.message || "Failed to start download job." });
  }
});

// 3. GET /api/media/status/:jobId
app.get("/api/media/status/:jobId", (req: Request, res: Response) => {
  const job = jobs.get(req.params.jobId);
  if (!job) {
    return res.status(404).json({ error: "Download job not found or expired." });
  }
  res.json({
    jobId: job.jobId,
    status: job.status,
    progress: job.progress,
    speedBytesPerSec: job.speedBytesPerSec,
    downloadedBytes: job.downloadedBytes,
    totalBytes: job.totalBytes,
    filename: job.filename,
    mimeType: job.mimeType,
    error: job.error,
  });
});

// 4. GET /api/media/file/:jobId
app.get("/api/media/file/:jobId", (req: Request, res: Response) => {
  const job = jobs.get(req.params.jobId);
  if (!job || job.status !== "completed" || !job.outputFilePath || !fs.existsSync(job.outputFilePath)) {
    return res.status(404).json({ error: "Completed media file not available." });
  }
  const stat = fs.statSync(job.outputFilePath);
  res.setHeader("Content-Type", job.mimeType || "application/octet-stream");
  res.setHeader("Content-Length", stat.size);
  res.setHeader("Content-Disposition", `attachment; filename="${job.filename || "media.mp4"}"`);
  const stream = fs.createReadStream(job.outputFilePath);
  stream.pipe(res);
});

// 5. POST /api/media/cancel/:jobId
app.post("/api/media/cancel/:jobId", (req: Request, res: Response) => {
  const job = jobs.get(req.params.jobId);
  if (!job) {
    return res.status(404).json({ error: "Job not found." });
  }
  job.status = "cancelled";
  job.process?.kill("SIGTERM");
  cleanupJobFiles(job.jobId);
  jobs.delete(job.jobId);
  res.json({ jobId: job.jobId, status: "cancelled" });
});

app.listen(PORT, () => {
  console.log(`Dragon Tool Media Downloader Backend listening on port ${PORT}`);
});
