const $ = (selector) => document.querySelector(selector);

const state = { file: null, result: null };
const tuningOptions = {
  guitar: [
    ["standard", "Standard · E A D G B E"],
    ["drop-d", "Drop D · D A D G B E"],
    ["dadgad", "DADGAD · D A D G A D"]
  ],
  bass: [
    ["standard", "4-string standard · E A D G"],
    ["drop-d", "4-string Drop D · D A D G"],
    ["five-string", "5-string standard · B E A D G"]
  ]
};

const fileInput = $("#fileInput");
const dropzone = $("#dropzone");
const convertButton = $("#convertButton");
const buttonLabel = $("#buttonLabel");
const provider = $("#provider");
const apiKey = $("#apiKey");
const model = $("#model");
const baseUrl = $("#baseUrl");
const errorBox = $("#error");

function instrument() {
  return document.querySelector('input[name="instrument"]:checked').value;
}

function refreshTunings() {
  const tuning = $("#tuning");
  const previous = tuning.value;
  tuning.replaceChildren();
  for (const [value, label] of tuningOptions[instrument()]) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    tuning.append(option);
  }
  if ([...tuning.options].some((option) => option.value === previous)) tuning.value = previous;
}

function setFile(file) {
  errorBox.hidden = true;
  if (!file) return;
  const extension = file.name.toLowerCase().split(".").pop();
  if (!["xml", "musicxml", "mxl"].includes(extension)) {
    showError("Choose a .musicxml, .xml, or .mxl file.");
    return;
  }
  if (file.size > 8 * 1024 * 1024) {
    showError("This file is larger than 8 MB. Please simplify the score first.");
    return;
  }
  state.file = file;
  $("#fileTitle").textContent = file.name;
  $("#fileMeta").textContent = `${formatBytes(file.size)} · Click to choose another file`;
  dropzone.classList.add("has-file");
  convertButton.disabled = false;
  buttonLabel.textContent = "Generate playable TAB";
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function setProvider() {
  const enabled = provider.value !== "none";
  for (const field of [apiKey, model, baseUrl, $("#toggleKey")]) field.disabled = !enabled;
  model.placeholder = provider.value === "deepseek" ? "deepseek-chat" : "gpt-4.1-mini";
}

function showError(message) {
  errorBox.textContent = message;
  errorBox.hidden = false;
}

async function convert() {
  if (!state.file) return;
  if (provider.value !== "none" && !apiKey.value.trim()) {
    showError("AI refinement requires an API Key. You can also select “Do not use AI.”");
    apiKey.focus();
    return;
  }
  errorBox.hidden = true;
  convertButton.disabled = true;
  convertButton.classList.add("loading");
  buttonLabel.textContent = provider.value === "none" ? "Analyzing fretboard positions…" : "Analyzing and asking AI…";
  try {
    const body = await state.file.arrayBuffer();
    const response = await fetch("/api/convert", {
      method: "POST",
      headers: {
        "Content-Type": "application/octet-stream",
        "X-Instrument": instrument(),
        "X-Tuning": $("#tuning").value,
        "X-Style": $("#style").value,
        "X-AI-Provider": provider.value,
        "X-API-Key": apiKey.value.trim(),
        "X-AI-Model": model.value.trim(),
        "X-AI-Base-URL": baseUrl.value.trim()
      },
      body
    });
    const payload = await response.json().catch(() => ({ error: `The server returned HTTP ${response.status}` }));
    if (!response.ok) throw new Error(payload.error || "Conversion failed");
    state.result = payload;
    renderResult(payload);
  } catch (error) {
    showError(error.message || "Conversion failed. Check the file format and try again.");
  } finally {
    convertButton.disabled = false;
    convertButton.classList.remove("loading");
    buttonLabel.textContent = "Generate TAB again";
  }
}

function renderResult(result) {
  $("#resultTitle").textContent = result.title;
  $("#resultMeta").textContent = `${result.partName} · ${result.instrument} · ${result.aiStatus}`;
  $("#asciiTab").textContent = result.asciiTab;
  const stats = [
    [result.stats.measures, "Measures"],
    [result.stats.notes, "Notes"],
    [result.stats.events, "Fingering events"],
    [result.stats.averageFret, "Average fret"]
  ];
  const statsNode = $("#stats");
  statsNode.replaceChildren();
  for (const [value, label] of stats) {
    const cell = document.createElement("div");
    cell.className = "stat";
    const strong = document.createElement("strong");
    strong.textContent = value;
    const span = document.createElement("span");
    span.textContent = label;
    cell.append(strong, span);
    statsNode.append(cell);
  }

  const warningNode = $("#warnings");
  warningNode.replaceChildren();
  warningNode.hidden = !result.warnings.length;
  if (result.warnings.length) warningNode.textContent = result.warnings.join(" · ");
  renderEventMap(result.events);
  $("#results").hidden = false;
  $("#results").scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderEventMap(events) {
  const map = new Map();
  for (const event of events) {
    if (!map.has(event.measure)) map.set(event.measure, []);
    map.get(event.measure).push(event);
  }
  const root = $("#eventMap");
  root.replaceChildren();
  for (const [measure, measureEvents] of map) {
    const row = document.createElement("div");
    row.className = "measure-row";
    const label = document.createElement("div");
    label.className = "measure-label";
    label.textContent = `Measure ${measure}`;
    const chips = document.createElement("div");
    chips.className = "event-chips";
    for (const event of measureEvents) {
      const chip = document.createElement("div");
      chip.className = "event-chip";
      const note = document.createElement("strong");
      note.textContent = event.notes.join("+");
      const position = document.createElement("span");
      position.textContent = event.positions.length
        ? event.positions.map((item) => `S${item.string}/F${item.fret}`).join(" · ")
        : "Out of range";
      chip.append(note, position);
      chips.append(chip);
    }
    row.append(label, chips);
    root.append(row);
  }
}

function safeName() {
  const title = state.result?.title || "fretflow-tab";
  const cleaned = title.replace(/[\\/:*?"<>|]+/g, "-").trim();
  return cleaned || "fretflow-tab";
}

function downloadBlob(data, type, filename) {
  const url = URL.createObjectURL(new Blob([data], { type }));
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function downloadMusicXml() {
  if (!state.result) return;
  const binary = atob(state.result.musicXmlBase64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  downloadBlob(bytes, "application/vnd.recordare.musicxml+xml", `${safeName()}-TAB.musicxml`);
}

fileInput.addEventListener("change", () => setFile(fileInput.files[0]));
for (const eventName of ["dragenter", "dragover"]) {
  dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.add("dragging"); });
}
for (const eventName of ["dragleave", "drop"]) {
  dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.remove("dragging"); });
}
dropzone.addEventListener("drop", (event) => setFile(event.dataTransfer.files[0]));
document.querySelectorAll('input[name="instrument"]').forEach((radio) => radio.addEventListener("change", refreshTunings));
provider.addEventListener("change", setProvider);
$("#toggleKey").addEventListener("click", () => {
  apiKey.type = apiKey.type === "password" ? "text" : "password";
  $("#toggleKey").textContent = apiKey.type === "password" ? "Show" : "Hide";
});
convertButton.addEventListener("click", convert);
$("#copyButton").addEventListener("click", async () => {
  if (!state.result) return;
  await navigator.clipboard.writeText(state.result.asciiTab);
  $("#copyButton").textContent = "Copied";
  setTimeout(() => { $("#copyButton").textContent = "Copy TAB"; }, 1600);
});
$("#downloadXml").addEventListener("click", downloadMusicXml);
$("#downloadTxt").addEventListener("click", () => {
  if (state.result) downloadBlob(state.result.asciiTab, "text/plain;charset=utf-8", `${safeName()}-TAB.txt`);
});

refreshTunings();
setProvider();
