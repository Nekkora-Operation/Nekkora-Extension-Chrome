# CHROME-002 — desempenho medido (antes × depois)

Gerado em 2026-10-14T01:56:00.501635Z · Chrome for Testing 155.0.8059.39 · Java 21.0.12

| Elementos | Nós | Captura síncrona (ms) | Bloqueio síncrono (ms) | Captura cooperativa (ms) | Fatias | Maior fatia (ms) | Bloqueio cooperativo (ms) | Ciclo sessão (ms) | Seleção reutilizada (ms) | Export anterior (ms) | Export em fluxo (ms) | Heap usado/comprometido/máx (MB) | RSS host (MB) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 100 | 92 | 4.3 | 7.7 | 2.4 | 1 | 1.4 | 6.2 | 27.1 | 34.1 | 43.63 | 89.84 | 9.8 / 108.0 / 7992.0 | 174.56 |
| 1000 | 932 | 19.1 | 23.9 | 17.9 | 2 | 8.2 | 17.7 | 61.26 | 38.91 | 159.23 | 208.22 | 13.8 / 108.0 / 7992.0 | 246.88 |
| 5000 | 4666 | 117.6 | 131.3 | 82.2 | 7 | 8.5 | 26.9 | 162.52 | 54.1 | 924.83 | 553.62 | 31.5 / 216.0 / 7992.0 | 355.36 |
| 10000 | 9332 | 327.1 | 349.3 | 146.1 | 11 | 13.7 | 24.9 | 276.89 | 76.17 | 2488.47 | 1008.22 | 53.5 / 268.0 / 7992.0 | 601.93 |
| 20000 | 18666 | 1047.3 | 1093.1 | 294.8 | 22 | 11.5 | 25.8 | 507.43 | 173.58 | 11098.02 | 2232.28 | 98.0 / 456.0 / 7992.0 | 594.45 |

## cooperativeConsistency

```json
{
  "insertedDuringCapture" : 60,
  "bufferedOnConfirm" : 19,
  "incrementalNodes" : 9572,
  "fullNodes" : 9572,
  "slices" : 38,
  "maxSliceMs" : 10.6
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
  "hostMaxDeltaMs" : 7.1,
  "hostAvgDeltaMs" : 2.07,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 50,
  "generatedMutations" : 250,
  "rawMutationRecords" : 511,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.2,
  "hostMaxDeltaMs" : 2.59,
  "hostAvgDeltaMs" : 2.0,
  "syncState" : "SYNCHRONIZED"
}, {
  "ratePerSecond" : 100,
  "generatedMutations" : 500,
  "rawMutationRecords" : 1021,
  "deltasApplied" : 10,
  "resyncs" : 0,
  "browserMaxDeltaMs" : 1.6,
  "hostMaxDeltaMs" : 3.79,
  "hostAvgDeltaMs" : 2.2,
  "syncState" : "SYNCHRONIZED"
} ]
```

## memory

```json
{
  "idleHeapUsedMb" : 9.1,
  "idleRssMb" : 100.29,
  "single_100" : {
    "heapUsedMb" : 9.2,
    "heapCommittedMb" : 56.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 100.23,
    "retainedCaptures" : 1
  },
  "single_1000" : {
    "heapUsedMb" : 10.0,
    "heapCommittedMb" : 40.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 93.95,
    "retainedCaptures" : 1
  },
  "single_10000" : {
    "heapUsedMb" : 18.5,
    "heapCommittedMb" : 108.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 141.96,
    "retainedCaptures" : 1
  },
  "threeLarge" : {
    "heapUsedMb" : 37.3,
    "heapCommittedMb" : 160.0,
    "heapMaxMb" : 7992.0,
    "rssMb" : 193.98,
    "captureSessions" : 3,
    "deltaVsIdleMb" : 28.2
  },
  "oneLargeDeltaVsIdleMb" : 9.4,
  "afterRelease" : {
    "heapUsedMb" : 9.1,
    "rssMb" : 156.81,
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
    "pending" : 6,
    "registrySize" : 266,
    "hostHeapUsedMb" : 10.1,
    "hostRssMb" : 297.6,
    "pageHeapUsedMb" : 1.5
  }, {
    "t" : 60,
    "status" : "ACTIVE",
    "deltasApplied" : 101,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 6,
    "registrySize" : 276,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 222.89,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 90,
    "status" : "ACTIVE",
    "deltasApplied" : 152,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 5,
    "registrySize" : 276,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 173.76,
    "pageHeapUsedMb" : 1.9
  }, {
    "t" : 120,
    "status" : "ACTIVE",
    "deltasApplied" : 203,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 276,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 157.52,
    "pageHeapUsedMb" : 2.2
  }, {
    "t" : 150,
    "status" : "ACTIVE",
    "deltasApplied" : 254,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 276,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 163.87,
    "pageHeapUsedMb" : 1.8
  }, {
    "t" : 180,
    "status" : "ACTIVE",
    "deltasApplied" : 305,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 7,
    "registrySize" : 276,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 163.95,
    "pageHeapUsedMb" : 2.0
  }, {
    "t" : 210,
    "status" : "ACTIVE",
    "deltasApplied" : 356,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 4,
    "registrySize" : 281,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 164.02,
    "pageHeapUsedMb" : 2.1
  }, {
    "t" : 240,
    "status" : "ACTIVE",
    "deltasApplied" : 407,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 281,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 164.17,
    "pageHeapUsedMb" : 1.6
  }, {
    "t" : 270,
    "status" : "ACTIVE",
    "deltasApplied" : 458,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 3,
    "registrySize" : 280,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 164.54,
    "pageHeapUsedMb" : 1.7
  }, {
    "t" : 300,
    "status" : "ACTIVE",
    "deltasApplied" : 509,
    "deltasRejected" : 0,
    "resyncs" : 0,
    "nodes" : 271,
    "pending" : 2,
    "registrySize" : 281,
    "hostHeapUsedMb" : 10.3,
    "hostRssMb" : 164.52,
    "pageHeapUsedMb" : 1.9
  } ],
  "generatedMutations" : 3051,
  "deltasApplied" : 510,
  "resyncs" : 0,
  "deltasRejected" : 0,
  "fullCaptures" : 1,
  "finalSyncState" : "SYNCHRONIZED",
  "heapAt1MinMb" : 10.3,
  "heapEndMb" : 10.3,
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
