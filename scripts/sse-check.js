// SSE 流文件校验：解析事件，校验 快照+增量 与 done 终稿的一致性（不丢不重）
const fs = require('fs')
const file = process.argv[2]
const raw = fs.readFileSync(file, 'utf8')
const events = []
for (const block of raw.split('\n\n')) {
  const lines = block.split('\n').filter(Boolean)
  const ev = { event: null, id: null, data: null }
  for (const line of lines) {
    if (line.startsWith('event:')) ev.event = line.slice(6).trim()
    else if (line.startsWith('id:')) ev.id = line.slice(3).trim()
    else if (line.startsWith('data:')) ev.data = line.slice(5).trim()
  }
  if (ev.event) events.push(ev)
}
const deltas = events.filter((e) => e.event === 'delta')
const snaps = events.filter((e) => e.event === 'full-delta')
const dones = events.filter((e) => e.event === 'done')
const errors = events.filter((e) => e.event === 'error')
const snapText = snaps.map((e) => JSON.parse(e.data).text).join('')
const deltaText = deltas.map((e) => JSON.parse(e.data).text).join('')
const doneMsg = dones.length ? JSON.parse(dones[0].data).message : null
const doneContent = doneMsg ? doneMsg.content : null

console.log(`deltas=${deltas.length} snapshots=${snaps.length} done=${dones.length} error=${errors.length}`)
console.log(`deltaTextChars=${deltaText.length} snapChars=${snapText.length} doneChars=${doneContent ? doneContent.length : 0}`)
if (deltas.length) {
  const liveMatch = deltaText === doneContent
  console.log(`live-path(纯增量拼接==终稿): ${liveMatch ? 'PASS' : 'FAIL'}`)
  if (!liveMatch) console.log(`  deltaHead=[${deltaText.slice(0, 40)}] doneHead=[${(doneContent || '').slice(0, 40)}]`)
}
if (snaps.length) {
  const combined = snapText + deltaText
  const prefix = doneContent.startsWith(snapText)
  const resumeMatch = combined === doneContent
  console.log(`resume-path(快照+后续增量==终稿): ${resumeMatch ? 'PASS' : 'FAIL'} (快照是终稿前缀: ${prefix ? 'yes' : 'NO!'})`)
  if (!resumeMatch) console.log(`  combinedChars=${combined.length} doneChars=${doneContent.length}`)
}
if (errors.length) console.log(`error payload: ${errors[0].data}`)
