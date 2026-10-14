# CHROME-002 — desempenho medido (antes × depois)

Gerado em 2026-10-14T18:13:42.695047100Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12

| Elementos | Nós | Captura síncrona (ms) | Bloqueio síncrono (ms) | Captura cooperativa (ms) | Fatias | Maior fatia (ms) | Bloqueio cooperativo (ms) | Ciclo sessão (ms) | Seleção reutilizada (ms) | Export anterior (ms) | Export em fluxo (ms) | Heap usado/comprometido/máx (MB) | RSS host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.8 | 6.5 | 3.0 | 1 | 1.5 | 6.2 | 29.85 | 40.04 | 52.21 | 98.3 | 11.2 / 108.0 / 7992.0 | 177.16 |
| 1000 | 932 | 24.5 | 28.4 | 20.3 | 2 | 8.3 | 19.6 | 59.51 | 47.87 | 175.15 | 218.36 | 15.3 / 108.0 / 7992.0 | 235.97 |
| 5000 | 4666 | 125.5 | 139.8 | 77.9 | 6 | 8.1 | 24.7 | 171.02 | 65.06 | 1011.25 | 634.02 | 33.3 / 228.0 / 7992.0 | 383.84 |
| 10000 | 9332 | 349.1 | 372.0 | 154.4 | 12 | 9.5 | 22.5 | 292.28 | 97.07 | 2779.12 | 1173.57 | 55.7 / 280.0 / 7992.0 | 448.08 |
| 20000 | 18666 | 1109.5 | 1177.0 | 330.6 | 25 | 11.0 | 25.9 | 590.05 | 206.39 | 12918.86 | 2605.7 | 100.5 / 440.0 / 7992.0 | 880.6 |

## cooperativeConsistency

```json
{
  "insertedDuringCapture" : 60,
  "bufferedOnConfirm" : 20,
  "incrementalNodes" : 9572,
  "fullNodes" : 9572,
  "slices" : 39,
  "maxSliceMs" : 10.1
}
```

## rapidRates

```json
[ {
  "ratePerSecond" : 10,
  "generatedMutations" : 50,
  "rawMutationRecords" : 103,
  "deltasApplied" : 9,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 0.8,
  "hostMaxDeltaMs" : 4.68,
  "hostAvgDeltaMs" : 1.86,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 50,
  "generatedMutations" : 250,
  "rawMutationRecords" : 511,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.2,
  "hostMaxDeltaMs" : 3.91,
  "hostAvgDeltaMs" : 2.25,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 100,
  "generatedMutations" : 501,
  "rawMutationRecords" : 1023,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.5,
  "hostMaxDeltaMs" : 2.39,
  "hostAvgDeltaMs" : 2.1,
  "syncState" : "SYNCHRONIZED"
} ]
```

## memory

```json
{
  "idleHeapUsedMb" : 10.3,
  "idleRssMb" : 107.29,
  "single_100" : {
    "heapUsedMb" : 10.4,
    "heapCommittedMb" : 56.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 110.39,
    "retainedCaptures" : 1
  },
  "single_1000" : {
    "heapUsedMb" : 11.2,
    "heapCommittedMb" : 40.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 102.82,
    "retainedCaptures" : 1
  },
  "single_10000" : {
    "heapUsedMb" : 19.7,
    "heapCommittedMb" : 108.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 134.84,
    "retainedCaptures" : 1
  },
  "threeLarge" : {
    "heapUsedMb" : 38.5,
    "heapCommittedMb" : 160.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 226.37,
    "captureSessions" : 3,
    "deltaVsIdleMb" : 28.2
  },
  "oneLargeDeltaVsIdleMb" : 9.4,
  "afterRelease" : {
    "heapUsedMb" : 10.3,
    "rssMb" : 181.54,
    "captureSessions" : 0
  }
}
```

## incrementalVsFull

```json
{
  "elementsIncremental" : 64,
  "elementsFull" : 64,
  "differences" : 0
}
```

## longSession_5min

```json
{
  "minutes" : 5,
  "ratePerSecond" : 10,
  "samples" : [ {
    "t" : 30,
    "status" : "ACTIVE",
    "deltasApplied" : 50,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 7,
    "registrySize" : 271,
    "hostHeapUsedMb" : 11.6,
    "hostRssMb" : 286.55,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 60,
    "status" : "ACTIVE",
    "deltasApplied" : 101,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 153.74,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 90,
    "status" : "ACTIVE",
    "deltasApplied" : 152,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 5,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 176.32,
    "pageHeapUsedMb" : 2.4
  }, {
    "t" : 120,
    "status" : "ACTIVE",
    "deltasApplied" : 203,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 176.39,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 150,
    "status" : "ACTIVE",
    "deltasApplied" : 254,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 5,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 178.51,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 180,
    "status" : "ACTIVE",
    "deltasApplied" : 305,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 8,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 178.52,
    "pageHeapUsedMb" : 2.3
  }, {
    "t" : 210,
    "status" : "ACTIVE",
    "deltasApplied" : 356,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.8,
    "hostRssMb" : 178.52,
    "pageHeapUsedMb" : 2.6
  }, {
    "t" : 240,
    "status" : "ACTIVE",
    "deltasApplied" : 407,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 280,
    "hostHeapUsedMb" : 11.8,
    "hostRssMb" : 178.67,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 270,
    "status" : "ACTIVE",
    "deltasApplied" : 459,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 179.17,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 300,
    "status" : "ACTIVE",
    "deltasApplied" : 510,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 179.14,
    "pageHeapUsedMb" : 2.4
  } ],
  "generatedMutations" : 3053,
  "deltasApplied" : 512,
  "resyncs" : 0,
  "deltasRejected" : 0,
  "fullCaptures" : 1,
  "finalSyncState" : "SYNCHRONIZED",
  "heapAt1MinMb" : 11.7,
  "heapEndMb" : 11.8,
  "maxRegistrySize" : 281,
  "initialNodes" : 271
}
```

## longSession_15min

```json
{
  "minutes" : 15,
  "ratePerSecond" : 10,
  "samples" : [ {
    "t" : 30,
    "status" : "ACTIVE",
    "deltasApplied" : 51,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 271,
    "hostHeapUsedMb" : 7.7,
    "hostRssMb" : 79.25,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 120,
    "status" : "ACTIVE",
    "deltasApplied" : 203,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 0,
    "registrySize" : 276,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 98.2,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 210,
    "status" : "ACTIVE",
    "deltasApplied" : 356,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 281,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 98.46,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 300,
    "status" : "ACTIVE",
    "deltasApplied" : 509,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 0,
    "registrySize" : 285,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 99.91,
    "pageHeapUsedMb" : 1.6
  }, {
    "t" : 390,
    "status" : "ACTIVE",
    "deltasApplied" : 663,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 0,
    "registrySize" : 286,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 106.46,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 480,
    "status" : "ACTIVE",
    "deltasApplied" : 816,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 291,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 103.38,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 570,
    "status" : "ACTIVE",
    "deltasApplied" : 969,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 5,
    "registrySize" : 291,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 104.15,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 660,
    "status" : "ACTIVE",
    "deltasApplied" : 1121,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 0,
    "registrySize" : 296,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 105.82,
    "pageHeapUsedMb" : 1.6
  }, {
    "t" : 750,
    "status" : "ACTIVE",
    "deltasApplied" : 1274,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 301,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 105.82,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 840,
    "status" : "ACTIVE",
    "deltasApplied" : 1427,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 301,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 106.44,
    "pageHeapUsedMb" : 1.8
  } ],
  "generatedMutations" : 9162,
  "deltasApplied" : 1531,
  "resyncs" : 0,
  "deltasRejected" : 0,
  "fullCaptures" : 1,
  "finalSyncState" : "SYNCHRONIZED",
  "heapAt1MinMb" : 7.8,
  "heapEndMb" : 7.9,
  "maxRegistrySize" : 306,
  "initialNodes" : 271
}
```

## longSession_30min

```json
{
  "minutes" : 30,
  "ratePerSecond" : 10,
  "samples" : [ {
    "t" : 30,
    "status" : "ACTIVE",
    "deltasApplied" : 51,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 275,
    "hostHeapUsedMb" : 7.7,
    "hostRssMb" : 79.33,
    "pageHeapUsedMb" : 1.5
  }, {
    "t" : 180,
    "status" : "ACTIVE",
    "deltasApplied" : 306,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 5,
    "registrySize" : 281,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 98.98,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 330,
    "status" : "ACTIVE",
    "deltasApplied" : 562,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 0,
    "registrySize" : 286,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 100.17,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 480,
    "status" : "ACTIVE",
    "deltasApplied" : 817,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 290,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 101.84,
    "pageHeapUsedMb" : 1.6
  }, {
    "t" : 630,
    "status" : "ACTIVE",
    "deltasApplied" : 1072,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 300,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 104.18,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 780,
    "status" : "ACTIVE",
    "deltasApplied" : 1327,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 306,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 100.86,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 930,
    "status" : "ACTIVE",
    "deltasApplied" : 1583,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 311,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 103.01,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 1080,
    "status" : "ACTIVE",
    "deltasApplied" : 1838,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 315,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 105.21,
    "pageHeapUsedMb" : 1.8
  }, {
    "t" : 1230,
    "status" : "ACTIVE",
    "deltasApplied" : 2094,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 326,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 99.42,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 1380,
    "status" : "ACTIVE",
    "deltasApplied" : 2349,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 331,
    "hostHeapUsedMb" : 7.9,
    "hostRssMb" : 100.93,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 1530,
    "status" : "ACTIVE",
    "deltasApplied" : 2604,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 276,
    "hostHeapUsedMb" : 8.0,
    "hostRssMb" : 110.96,
    "pageHeapUsedMb" : 1.8
  }, {
    "t" : 1680,
    "status" : "ACTIVE",
    "deltasApplied" : 2859,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 281,
    "hostHeapUsedMb" : 8.0,
    "hostRssMb" : 112.2,
    "pageHeapUsedMb" : 2.0
  } ],
  "generatedMutations" : 18011,
  "deltasApplied" : 3014,
  "resyncs" : 0,
  "deltasRejected" : 0,
  "fullCaptures" : 1,
  "finalSyncState" : "SYNCHRONIZED",
  "heapAt1MinMb" : 7.8,
  "heapEndMb" : 8.0,
  "maxRegistrySize" : 331,
  "initialNodes" : 271
}
```
