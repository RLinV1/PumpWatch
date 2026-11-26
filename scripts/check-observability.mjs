const PROMETHEUS = process.env.PROMETHEUS_URL ?? "http://127.0.0.1:9090";
const GRAFANA = process.env.GRAFANA_URL ?? "http://127.0.0.1:3001";
const LOG_SERVICES = ["proxy", "web", "api"];
const TIMEOUT_MS = Number(process.env.CHECK_TIMEOUT_MS ?? 180_000);

async function getJson(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error(`${url} timed out`)), 10_000);
  try {
    const res = await fetch(url, { signal: controller.signal });
    if (!res.ok) throw new Error(`${url} answered ${res.status}`);
    return await res.json();
  } finally {
    clearTimeout(timer);
  }
}

async function problems() {
  const found = [];
  const targets = await getJson(`${PROMETHEUS}/api/v1/targets?state=active`);
  for (const t of targets.data.activeTargets) {
    if (t.health !== "up") found.push(`target ${t.labels.job}/${t.labels.instance} is ${t.health}: ${t.lastError}`);
  }
  if (targets.data.activeTargets.length === 0) found.push("Prometheus has no active targets");

  const probes = await getJson(`${PROMETHEUS}/api/v1/query?query=${encodeURIComponent("probe_success")}`);
  if (probes.data.result.length === 0) found.push("no probe results yet");
  for (const p of probes.data.result) {
    if (p.value[1] !== "1") found.push(`probe ${p.metric.instance} failed`);
  }

  const labels = await getJson(`${GRAFANA}/api/datasources/proxy/uid/loki/loki/api/v1/label/service/values`);
  const services = labels.data ?? [];
  for (const s of LOG_SERVICES) {
    if (!services.includes(s)) found.push(`Loki has no logs for ${s} (has: ${services.join(", ") || "none"})`);
  }

  await getJson(`${GRAFANA}/api/dashboards/uid/pumpwatch-health`);
  return found;
}

const deadline = Date.now() + TIMEOUT_MS;
let last = [];
for (;;) {
  try {
    last = await problems();
  } catch (error) {
    last = [error.cause?.code ?? error.message];
  }
  if (last.length === 0) {
    console.log("observability ok: targets up, probes passing, logs flowing, dashboard present");
    process.exit(0);
  }
  if (Date.now() > deadline) break;
  await new Promise((resolve) => setTimeout(resolve, 10_000));
}
console.error(`observability check failed:\n- ${last.join("\n- ")}`);
process.exit(1);
