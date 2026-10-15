"""Gera as fixtures compartilhadas do contrato nekkora-bridge/1 (contracts/studio-bridge/fixtures).

Uso: python scripts/gen-studio-bridge-fixtures.py contracts/studio-bridge/fixtures
Depois de mudar o contrato ou as fixtures: rode BridgeContractTest (host) e copie a pasta contracts/studio-bridge e o
schema para o Nekkora Studio (nekkora-studio-bridge/src/test/resources/contracts) — BridgeContractFixturesTest acusa
qualquer divergência quando os dois repositórios estão lado a lado.
"""
import base64
import hashlib
import json
import os
import sys

root = sys.argv[1]
valid = os.path.join(root, "valid")
invalid = os.path.join(root, "invalid")
os.makedirs(valid, exist_ok=True)
os.makedirs(invalid, exist_ok=True)

P = "nekkora-bridge/1"
REC = "rec-3f1c2a9e-5b7d-4e21-9a0b-6c8d7e5f4a31"
OP = "op-8a2b4c6d-1e3f-4a5b-8c7d-9e0f1a2b3c4d"
TAB = "t-Q2f9LmZx7Kp1"
NONCE_S = base64.urlsafe_b64encode(hashlib.sha256(b"fixture-server-nonce").digest()).decode().rstrip("=")
NONCE_C = base64.urlsafe_b64encode(hashlib.sha256(b"fixture-client-nonce").digest()).decode().rstrip("=")
PROOF = base64.urlsafe_b64encode(hashlib.sha256(b"fixture-proof-not-a-real-hmac").digest()).decode().rstrip("=")

recording = {
    "recordingId": REC, "state": "RECORDING", "mode": "CAPTURE_AND_INTERACT",
    "tab": {"tabRef": TAB, "title": "Sistema de testes", "host": "127.0.0.1"},
    "capture": {"recorderAttached": True, "otherConsumers": 1, "captureStatus": "ACTIVE"},
    "coverage": {"state": "COMPLETE", "framesAttached": 0, "framesUnavailable": 0},
    "counters": {"segments": 1, "technicalCaptures": 1, "synchronizations": 4, "recoveries": 0},
    "lease": {"leaseMs": 30000, "expiresInMs": 29500},
    "startedAt": "2026-10-15T12:00:00.000Z", "updatedAt": "2026-10-15T12:00:05.000Z", "reason": None,
}
paused = dict(recording, state="PAUSED", capture={"recorderAttached": False, "otherConsumers": 1, "captureStatus": "ACTIVE"},
              reason="USER_PAUSED")
completed = dict(recording, state="COMPLETED", capture={"recorderAttached": False, "otherConsumers": 1, "captureStatus": "ACTIVE"},
                 reason="USER_FINISHED", lease={"leaseMs": 30000, "expiresInMs": 0})
summary = {"state": "COMPLETED", "durationMs": 42000,
           "counters": {"segments": 2, "technicalCaptures": 2, "synchronizations": 9, "recoveries": 0},
           "coverage": {"state": "COMPLETE", "framesAttached": 0, "framesUnavailable": 0},
           "pageMapExported": False, "actionCapture": "NOT_AVAILABLE", "limitations": ["ACTION_CAPTURE_NOT_AVAILABLE"]}
status = {"link": "CONNECTED", "extension": {"version": "0.4.0", "buildMode": "DEV"}, "host": {"version": "0.2.0", "engineBusy": False}}

fixtures_valid = {
    "handshake-challenge": {"protocol": P, "kind": "handshake", "type": "bridge.challenge", "seq": 1,
                            "payload": {"instanceId": "studio-7c1d", "serverNonce": NONCE_S, "accepts": [P], "studio": {"version": "1.0.0-SNAPSHOT"}}},
    "handshake-hello": {"protocol": P, "kind": "handshake", "type": "bridge.hello", "seq": 1,
                        "payload": {"instanceId": "studio-7c1d", "clientNonce": NONCE_C, "proof": PROOF,
                                    "client": {"hostVersion": "0.2.0", "nativeProtocol": "nekkora-nm/1", "extension": {"version": "0.4.0", "buildMode": "DEV"}}}},
    "handshake-welcome": {"protocol": P, "kind": "handshake", "type": "bridge.welcome", "seq": 2,
                          "payload": {"proof": PROOF, "heartbeatMs": 5000, "maxFrameBytes": 262144}},
    "handshake-rejected": {"protocol": P, "kind": "handshake", "type": "bridge.rejected", "seq": 2,
                           "payload": {"error": {"code": "AUTH_FAILED", "message": "Credencial do Bridge inválida."}}},
    "command-status": {"protocol": P, "kind": "command", "type": "bridge.status", "id": "c-1", "seq": 3, "payload": {}},
    "command-tabs-list": {"protocol": P, "kind": "command", "type": "browser.tabs.list", "id": "c-2", "seq": 4, "payload": {}},
    "command-start": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-3", "seq": 5,
                      "payload": {"recordingId": REC, "operationId": OP, "tabRef": TAB, "mode": "CAPTURE_AND_INTERACT", "leaseMs": 30000}},
    "command-pause": {"protocol": P, "kind": "command", "type": "studio.recording.pause", "id": "c-4", "seq": 6,
                      "payload": {"recordingId": REC, "operationId": "op-pause-1"}},
    "command-mode": {"protocol": P, "kind": "command", "type": "studio.recording.mode", "id": "c-5", "seq": 7,
                     "payload": {"recordingId": REC, "operationId": "op-mode-1", "mode": "CAPTURE_ONLY"}},
    "command-finish": {"protocol": P, "kind": "command", "type": "studio.recording.finish", "id": "c-6", "seq": 8,
                       "payload": {"recordingId": REC, "operationId": "op-finish-1"}},
    "command-renew": {"protocol": P, "kind": "command", "type": "studio.recording.renew", "id": "c-7", "seq": 9,
                      "payload": {"recordingId": REC}},
    "command-recording-status": {"protocol": P, "kind": "command", "type": "studio.recording.status", "id": "c-8", "seq": 10, "payload": {}},
    "response-status": {"protocol": P, "kind": "response", "type": "bridge.status", "id": "c-1", "seq": 2, "ok": True, "payload": status, "error": None},
    "response-heartbeat": {"protocol": P, "kind": "response", "type": "bridge.heartbeat", "id": "c-9", "seq": 3, "ok": True,
                           "payload": {"at": "2026-10-15T12:00:00.000Z"}, "error": None},
    "response-tabs": {"protocol": P, "kind": "response", "type": "browser.tabs.list", "id": "c-2", "seq": 4, "ok": True,
                      "payload": {"tabs": [
                          {"tabRef": TAB, "title": "Sistema de testes", "host": "127.0.0.1", "access": "LAB", "active": True, "recording": False},
                          {"tabRef": "t-Hh28sKq0aaPz", "title": "Wikipedia, a enciclopédia livre", "host": "pt.wikipedia.org", "access": "AUTHORIZED", "active": False, "recording": False},
                          {"tabRef": "t-Zz11Aa22Bb33", "title": None, "host": "portal.exemplo.com", "access": "PERMISSION_REQUIRED", "active": False, "recording": False},
                          {"tabRef": "t-Yy99Xx88Ww77", "title": None, "host": None, "access": "BROWSER_INTERNAL", "active": False, "recording": False}],
                          "truncated": False}, "error": None},
    "response-start": {"protocol": P, "kind": "response", "type": "studio.recording.start", "id": "c-3", "seq": 5, "ok": True,
                       "payload": {"recording": recording, "duplicate": False}, "error": None},
    "response-pause": {"protocol": P, "kind": "response", "type": "studio.recording.pause", "id": "c-4", "seq": 6, "ok": True,
                       "payload": {"recording": paused, "duplicate": False}, "error": None},
    "response-finish": {"protocol": P, "kind": "response", "type": "studio.recording.finish", "id": "c-6", "seq": 7, "ok": True,
                        "payload": {"recording": completed, "summary": summary, "duplicate": False}, "error": None},
    "response-renew": {"protocol": P, "kind": "response", "type": "studio.recording.renew", "id": "c-7", "seq": 8, "ok": True,
                       "payload": {"recordingId": REC, "state": "RECORDING", "expiresInMs": 30000}, "error": None},
    "response-recording-status": {"protocol": P, "kind": "response", "type": "studio.recording.status", "id": "c-8", "seq": 9, "ok": True,
                                  "payload": {"recordings": [recording]}, "error": None},
    "response-error-origin": {"protocol": P, "kind": "response", "type": "studio.recording.start", "id": "c-10", "seq": 10, "ok": False, "payload": None,
                              "error": {"code": "ORIGIN_NOT_ALLOWED", "reason": "PERMISSION_REQUIRED",
                                        "message": "Este site ainda não está autorizado. Abra a Nekkora Extension no Chrome e conceda acesso ao site.", "retryable": False}},
    "event-connected": {"protocol": P, "kind": "event", "type": "bridge.connected", "seq": 3, "payload": status},
    "event-disconnected": {"protocol": P, "kind": "event", "type": "bridge.disconnected", "seq": 11, "payload": {"reason": "CHROME_DISCONNECTED"}},
    "event-paused": {"protocol": P, "kind": "event", "type": "studio.recording.paused", "seq": 7,
                     "payload": {"recordingId": REC, "operationId": "op-pause-1", "recording": paused}},
    "event-degraded": {"protocol": P, "kind": "event", "type": "studio.recording.degraded", "seq": 12,
                       "payload": {"recordingId": REC, "operationId": None,
                                   "recording": dict(recording, state="DEGRADED", reason="TAB_CLOSED",
                                                     capture={"recorderAttached": False, "otherConsumers": 0, "captureStatus": None},
                                                     coverage={"state": "UNKNOWN", "framesAttached": 0, "framesUnavailable": 0})}},
    "event-finished": {"protocol": P, "kind": "event", "type": "studio.recording.finished", "seq": 13,
                       "payload": {"recordingId": REC, "operationId": "op-finish-1", "recording": completed, "summary": summary}},
}

fixtures_invalid = {
    # versão errada do protocolo
    "wrong-protocol": {"protocol": "nekkora-bridge/2", "kind": "command", "type": "bridge.status", "id": "c-1", "seq": 1, "payload": {}},
    # comando fora da lista explícita (nenhum proxy genérico)
    "unknown-command": {"protocol": P, "kind": "command", "type": "page.capture", "id": "c-1", "seq": 1, "payload": {}},
    # tentativa de executar script arbitrário
    "script-injection": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-1", "seq": 1,
                         "payload": {"recordingId": REC, "operationId": OP, "tabRef": TAB, "mode": "CAPTURE_ONLY", "leaseMs": 30000,
                                     "script": "document.cookie"}},
    # id interno do Chrome no lugar da referência opaca
    "raw-tab-id": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-1", "seq": 1,
                   "payload": {"recordingId": REC, "operationId": OP, "tabRef": 1234, "mode": "CAPTURE_ONLY", "leaseMs": 30000}},
    "invalid-mode": {"protocol": P, "kind": "command", "type": "studio.recording.mode", "id": "c-1", "seq": 1,
                     "payload": {"recordingId": REC, "operationId": OP, "mode": "AUTOPILOT"}},
    "lease-too-long": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-1", "seq": 1,
                       "payload": {"recordingId": REC, "operationId": OP, "tabRef": TAB, "mode": "CAPTURE_ONLY", "leaseMs": 3600000}},
    # finalização que diz ter exportado o PageMap
    "finish-with-export": {"protocol": P, "kind": "response", "type": "studio.recording.finish", "id": "c-6", "seq": 7, "ok": True,
                           "payload": {"recording": completed, "summary": dict(summary, pageMapExported=True), "duplicate": False}, "error": None},
    # PageMap pelo canal de controle
    "pagemap-in-event": {"protocol": P, "kind": "event", "type": "studio.recording.finished", "seq": 13,
                         "payload": {"recordingId": REC, "operationId": "op-finish-1", "recording": completed, "summary": summary,
                                     "pageMap": {"schemaVersion": "0.2.0", "elements": []}}},
    # URL completa da aba (só o host pode sair da extensão)
    "tab-with-url": {"protocol": P, "kind": "response", "type": "browser.tabs.list", "id": "c-2", "seq": 4, "ok": True,
                     "payload": {"tabs": [{"tabRef": TAB, "title": "x", "host": "127.0.0.1", "url": "http://127.0.0.1/?token=abc",
                                           "access": "LAB", "active": True, "recording": False}], "truncated": False}, "error": None},
    "error-without-code": {"protocol": P, "kind": "response", "type": "studio.recording.pause", "id": "c-4", "seq": 6, "ok": False, "payload": None,
                           "error": {"message": "falhou"}},
    "ok-with-error": {"protocol": P, "kind": "response", "type": "studio.recording.pause", "id": "c-4", "seq": 6, "ok": True,
                      "payload": {"recording": paused, "duplicate": False}, "error": {"code": "INTERNAL_ERROR", "message": "x"}},
    "short-proof": {"protocol": P, "kind": "handshake", "type": "bridge.hello", "seq": 1,
                    "payload": {"instanceId": "studio-7c1d", "clientNonce": NONCE_C, "proof": "abc",
                                "client": {"hostVersion": "0.2.0", "nativeProtocol": "nekkora-nm/1", "extension": {"version": "0.4.0", "buildMode": "DEV"}}}},
    "secret-in-hello": {"protocol": P, "kind": "handshake", "type": "bridge.hello", "seq": 1,
                        "payload": {"instanceId": "studio-7c1d", "clientNonce": NONCE_C, "proof": PROOF, "secret": "plain-secret",
                                    "client": {"hostVersion": "0.2.0", "nativeProtocol": "nekkora-nm/1", "extension": {"version": "0.4.0", "buildMode": "DEV"}}}},
    "paused-state-unknown": {"protocol": P, "kind": "event", "type": "studio.recording.paused", "seq": 7,
                             "payload": {"recordingId": REC, "operationId": "op-pause-1", "recording": dict(paused, state="PAUSING")}},
}

for name, body in fixtures_valid.items():
    with open(os.path.join(valid, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(body, f, ensure_ascii=False, indent=2)
        f.write("\n")
for name, body in fixtures_invalid.items():
    with open(os.path.join(invalid, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(body, f, ensure_ascii=False, indent=2)
        f.write("\n")
print(len(fixtures_valid), "válidas,", len(fixtures_invalid), "inválidas")
