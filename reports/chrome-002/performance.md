# CHROME-002 — desempenho medido (antes × depois)

Gerado em 2026-10-14T04:09:49.986408200Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12

| Elementos | Nós | Captura síncrona (ms) | Bloqueio síncrono (ms) | Captura cooperativa (ms) | Fatias | Maior fatia (ms) | Bloqueio cooperativo (ms) | Ciclo sessão (ms) | Seleção reutilizada (ms) | Export anterior (ms) | Export em fluxo (ms) | Heap usado/comprometido/máx (MB) | RSS host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 5.2 | 6.9 | 3.6 | 1 | 2.1 | 6.5 | 37.53 | 43.84 | 57.44 | 104.17 | 11.3 / 108.0 / 7992.0 | 174.21 |
| 1000 | 932 | 21.7 | 25.3 | 22.3 | 2 | 8.9 | 19.1 | 67.09 | 50.73 | 197.7 | 225.01 | 15.3 / 108.0 / 7992.0 | 247.57 |
| 5000 | 4666 | 120.2 | 136.3 | 77.0 | 6 | 11.8 | 20.5 | 161.28 | 72.11 | 1081.97 | 665.83 | 33.2 / 228.0 / 7992.0 | 382.61 |
| 10000 | 9332 | 330.2 | 351.4 | 153.4 | 11 | 9.7 | 28.9 | 308.12 | 100.34 | 2775.51 | 1176.51 | 55.6 / 268.0 / 7992.0 | 433.34 |
| 20000 | 18666 | 1151.6 | 1191.3 | 325.4 | 22 | 10.6 | 29.5 | 596.65 | 199.53 | 16695.86 | 2790.14 | 100.4 / 440.0 / 7992.0 | 576.98 |

## cooperativeConsistency

```json
{
  "insertedDuringCapture" : 60,
  "bufferedOnConfirm" : 21,
  "incrementalNodes" : 9572,
  "fullNodes" : 9572,
  "slices" : 40,
  "maxSliceMs" : 9.2
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
  "browserMaxDeltaMs" : 0.7,
  "hostMaxDeltaMs" : 5.56,
  "hostAvgDeltaMs" : 2.21,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 50,
  "generatedMutations" : 250,
  "rawMutationRecords" : 511,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.3,
  "hostMaxDeltaMs" : 3.88,
  "hostAvgDeltaMs" : 2.29,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 100,
  "generatedMutations" : 501,
  "rawMutationRecords" : 1023,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.5,
  "hostMaxDeltaMs" : 4.11,
  "hostAvgDeltaMs" : 2.44,
  "syncState" : "SYNCHRONIZED"
} ]
```

## memory

```json
{
  "idleHeapUsedMb" : 10.3,
  "idleRssMb" : 108.71,
  "single_100" : {
    "heapUsedMb" : 10.4,
    "heapCommittedMb" : 56.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 86.95,
    "retainedCaptures" : 1
  },
  "single_1000" : {
    "heapUsedMb" : 11.2,
    "heapCommittedMb" : 40.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 102.96,
    "retainedCaptures" : 1
  },
  "single_10000" : {
    "heapUsedMb" : 19.7,
    "heapCommittedMb" : 108.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 143.13,
    "retainedCaptures" : 1
  },
  "threeLarge" : {
    "heapUsedMb" : 38.5,
    "heapCommittedMb" : 188.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 224.46,
    "captureSessions" : 3,
    "deltaVsIdleMb" : 28.2
  },
  "oneLargeDeltaVsIdleMb" : 9.4,
  "afterRelease" : {
    "heapUsedMb" : 10.3,
    "rssMb" : 179.36,
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
    "pending" : 2,
    "registrySize" : 271,
    "hostHeapUsedMb" : 11.6,
    "hostRssMb" : 291.3,
    "pageHeapUsedMb" : 1.5
  }, {
    "t" : 60,
    "status" : "ACTIVE",
    "deltasApplied" : 101,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 271,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 203.31,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 90,
    "status" : "ACTIVE",
    "deltasApplied" : 152,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.8,
    "hostRssMb" : 162.07,
    "pageHeapUsedMb" : 1.6
  }, {
    "t" : 120,
    "status" : "ACTIVE",
    "deltasApplied" : 203,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 162.15,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 150,
    "status" : "ACTIVE",
    "deltasApplied" : 254,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 276,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 167.12,
    "pageHeapUsedMb" : 2.2
  }, {
    "t" : 180,
    "status" : "ACTIVE",
    "deltasApplied" : 305,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.8,
    "hostRssMb" : 167.19,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 210,
    "status" : "ACTIVE",
    "deltasApplied" : 356,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.8,
    "hostRssMb" : 167.19,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 240,
    "status" : "ACTIVE",
    "deltasApplied" : 407,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 167.34,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 270,
    "status" : "ACTIVE",
    "deltasApplied" : 458,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 168.0,
    "pageHeapUsedMb" : 2.2
  }, {
    "t" : 300,
    "status" : "ACTIVE",
    "deltasApplied" : 509,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 281,
    "hostHeapUsedMb" : 11.7,
    "hostRssMb" : 168.01,
    "pageHeapUsedMb" : 1.7
  } ],
  "generatedMutations" : 3054,
  "deltasApplied" : 511,
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
