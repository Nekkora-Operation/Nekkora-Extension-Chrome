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

# ------------------------------------------------------------------------------------------- STUDIO-003 (aditivo)
STEPS_STATE = {"enabled": True, "lastSequence": 3, "ackedSequence": 2, "pending": 1, "lost": 0}
recording_steps = dict(recording, steps=STEPS_STATE)
element_button = {
    "resolution": "RESOLVED", "kind": "BUTTON", "role": "button", "name": "Entrar", "tag": "button", "htmlId": "entrar", "htmlName": None,
    "sensitive": False,
    "locators": [
        {"strategy": "ID", "description": "id \"entrar\"", "css": "#entrar", "xpath": "//*[@id='entrar']", "matchCount": 1, "matchesTarget": True,
         "stability": "HEURISTIC_STABLE", "validation": "LIVE"},
        {"strategy": "ROLE_NAME", "description": "botão \"Entrar\"", "css": None, "xpath": "//button[normalize-space()='Entrar']", "matchCount": 1,
         "matchesTarget": True, "stability": "HEURISTIC_STABLE", "validation": "SNAPSHOT"}],
    "preferredLocator": 0,
    "accessPath": [{"type": "MAIN_DOCUMENT", "label": "Login de teste", "locator": None, "restriction": None},
                   {"type": "ELEMENT", "label": "Entrar", "locator": "#entrar", "restriction": None}],
    "context": [{"relation": "IN_FORM", "kind": "FORM", "name": "login"}],
    "lookalikes": 0,
    "correlation": {"targetId": "tgt-7f3a91c2", "nodeId": "n8", "captureId": "cap-12", "documentId": "doc:f0"},
}
element_password = dict(element_button, kind="PASSWORD_INPUT", role="textbox", name="Senha", tag="input", htmlId=None, sensitive=True,
                        locators=[{"strategy": "LABEL", "description": "campo \"Senha\"", "css": None, "xpath": "//input[@type='password']",
                                   "matchCount": 1, "matchesTarget": True, "stability": "HEURISTIC_STABLE", "validation": "SNAPSHOT"}],
                        context=[])
step_click = {
    "recordingId": REC, "eventId": "ev-1-3", "sequence": 3, "routeRef": "route-1", "observedAt": "2026-10-15T12:00:07.000Z",
    "mode": "CAPTURE_AND_INTERACT", "observation": "INTERACTION_OBSERVED", "action": "CLICK", "element": element_button,
    "parameters": {"valueCaptured": False, "option": None, "key": None},
    "context": {"url": "http://127.0.0.1:18765/lab/studio003/login.html", "title": "Login de teste", "frame": "MAIN",
                "after": {"navigation": "NEW_DOCUMENT", "url": "http://127.0.0.1:18765/lab/studio003/consulta.html"}},
    "evidence": ["FORM_SUBMITTED", "NAVIGATION_FOLLOWED", "LOCATORS_VERIFIED_LIVE"], "notes": [], "events": 4,
}
step_fill_password = dict(step_click, eventId="ev-1-2", sequence=2, action="FILL", element=element_password, evidence=["FOCUS_CLICK_ABSORBED"],
                          events=9, context={"url": "http://127.0.0.1:18765/lab/studio003/login.html", "title": "Login de teste", "frame": "MAIN"})
step_select = dict(step_click, eventId="ev-1-5", sequence=5, routeRef="route-2", action="SELECT_OPTION",
                   element=dict(element_button, kind="SELECT", role="combobox", name="Estado", tag="select", htmlId="estado", context=[]),
                   parameters={"valueCaptured": False, "option": {"label": "São Paulo", "value": "SP"}, "key": None}, evidence=[], events=2,
                   context={"url": "http://127.0.0.1:18765/lab/studio003/consulta.html", "title": "Consulta", "frame": "MAIN"})
step_proposed = dict(step_click, eventId="ev-1-6", sequence=6, mode="CAPTURE_ONLY", observation="PROPOSED", evidence=[], events=1,
                     context={"url": None, "title": None, "frame": "FRAME"})
step_unresolved = dict(step_click, eventId="ev-1-7", sequence=7, action="UNKNOWN", evidence=[], notes=["AMBIGUOUS_TARGET", "ELEMENT_UNRESOLVED"],
                       element={"resolution": "UNRESOLVED", "kind": "UNKNOWN", "role": None, "name": "Ícone", "tag": "span", "htmlId": None,
                                "htmlName": None, "sensitive": False, "locators": [], "preferredLocator": -1, "accessPath": [], "context": [],
                                "lookalikes": 0},
                       context={"url": "http://127.0.0.1:18765/lab/studio003/login.html", "title": "Login de teste", "frame": "MAIN"})
summary_available = dict(summary, actionCapture="AVAILABLE", limitations=[],
                         steps={"produced": 3, "acknowledged": 3, "rejected": 0, "unacknowledged": 0, "lost": 0,
                                "discarded": {"UNTRUSTED": 0, "NON_INTERACTIVE": 2, "FOCUS_ONLY": 1, "PROGRAMMATIC_SUBMIT": 0, "ABSORBED": 3,
                                              "AFTER_STOP": 0, "STALE_EPOCH": 0, "NOT_RECORDING": 0}})
summary_partial = dict(summary_available, actionCapture="PARTIAL", limitations=["STEPS_UNCONFIRMED", "INTERACTIONS_LOST"],
                       steps=dict(summary_available["steps"], acknowledged=2, unacknowledged=1, lost=1))

fixtures_valid.update({
    "s003-command-start-steps": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-30", "seq": 30,
                                 "payload": {"recordingId": REC, "operationId": "op-start-3", "tabRef": TAB, "mode": "CAPTURE_AND_INTERACT",
                                             "leaseMs": 30000, "capabilities": ["steps/1"], "routeRef": "route-1"}},
    "s003-command-ack": {"protocol": P, "kind": "command", "type": "studio.recording.steps.ack", "id": "c-31", "seq": 31,
                         "payload": {"recordingId": REC, "upToSequence": 3, "rejected": [3]}},
    "s003-command-sync": {"protocol": P, "kind": "command", "type": "studio.recording.steps.sync", "id": "c-32", "seq": 32,
                          "payload": {"recordingId": REC, "afterSequence": 2}},
    "s003-command-route": {"protocol": P, "kind": "command", "type": "studio.recording.route", "id": "c-33", "seq": 33,
                           "payload": {"recordingId": REC, "operationId": "op-route-1", "routeRef": "route-2"}},
    "s003-response-start-steps": {"protocol": P, "kind": "response", "type": "studio.recording.start", "id": "c-30", "seq": 40, "ok": True,
                                  "payload": {"recording": dict(recording_steps, steps={"enabled": True, "lastSequence": 0, "ackedSequence": 0, "pending": 0, "lost": 0}),
                                              "duplicate": False}, "error": None},
    "s003-response-ack": {"protocol": P, "kind": "response", "type": "studio.recording.steps.ack", "id": "c-31", "seq": 41, "ok": True,
                          "payload": {"recordingId": REC, "steps": STEPS_STATE}, "error": None},
    "s003-response-sync": {"protocol": P, "kind": "response", "type": "studio.recording.steps.sync", "id": "c-32", "seq": 42, "ok": True,
                           "payload": {"recordingId": REC, "resent": 1, "steps": STEPS_STATE}, "error": None},
    "s003-response-route": {"protocol": P, "kind": "response", "type": "studio.recording.route", "id": "c-33", "seq": 43, "ok": True,
                            "payload": {"recording": recording_steps, "boundarySequence": 3, "duplicate": False}, "error": None},
    "s003-response-renew-steps": {"protocol": P, "kind": "response", "type": "studio.recording.renew", "id": "c-34", "seq": 44, "ok": True,
                                  "payload": {"recordingId": REC, "state": "RECORDING", "expiresInMs": 30000, "steps": STEPS_STATE}, "error": None},
    "s003-response-finish-available": {"protocol": P, "kind": "response", "type": "studio.recording.finish", "id": "c-35", "seq": 45, "ok": True,
                                       "payload": {"recording": dict(completed, steps=dict(STEPS_STATE, ackedSequence=3, pending=0)),
                                                   "summary": summary_available, "duplicate": False}, "error": None},
    "s003-event-finished-partial": {"protocol": P, "kind": "event", "type": "studio.recording.finished", "seq": 46,
                                    "payload": {"recordingId": REC, "operationId": None, "recording": completed, "summary": summary_partial}},
    "s003-event-step-click": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 47, "payload": step_click},
    "s003-event-step-fill-password": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 48, "payload": step_fill_password},
    "s003-event-step-select": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 49, "payload": step_select},
    "s003-event-step-proposed": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 50, "payload": step_proposed},
    "s003-event-step-unresolved": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 51, "payload": step_unresolved},
})

fixtures_invalid.update({
    # valor digitado nunca trafega
    "s003-step-with-typed-value": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 60,
                                   "payload": dict(step_fill_password, parameters={"valueCaptured": True, "option": None, "key": None})},
    "s003-step-with-value-field": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 61,
                                   "payload": dict(step_fill_password, parameters={"valueCaptured": False, "option": None, "key": None, "value": "s3nh@"})},
    # PageMap/snapshot nunca vai junto da etapa
    "s003-step-with-pagemap": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 62,
                               "payload": dict(step_click, pageMap={"schemaVersion": "0.2.0", "elements": []})},
    "s003-step-element-with-nodes": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 63,
                                     "payload": dict(step_click, element=dict(element_button, nodes=[{"id": "n1"}]))},
    "s003-step-unknown-action": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 64,
                                 "payload": dict(step_click, action="NAVIGATE")},
    "s003-step-without-sequence": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 65,
                                   "payload": {k: v for k, v in step_click.items() if k != "sequence"}},
    "s003-step-as-lifecycle-payload": {"protocol": P, "kind": "event", "type": "studio.recording.step", "seq": 66,
                                       "payload": {"recordingId": REC, "operationId": None, "recording": recording}},
    "s003-ack-negative": {"protocol": P, "kind": "command", "type": "studio.recording.steps.ack", "id": "c-36", "seq": 67,
                          "payload": {"recordingId": REC, "upToSequence": -1}},
    "s003-start-unknown-capability": {"protocol": P, "kind": "command", "type": "studio.recording.start", "id": "c-37", "seq": 68,
                                      "payload": {"recordingId": REC, "operationId": "op-x", "tabRef": TAB, "mode": "CAPTURE_ONLY", "leaseMs": 30000,
                                                  "capabilities": ["eval/1"]}},
    "s003-summary-unknown-action-capture": {"protocol": P, "kind": "event", "type": "studio.recording.finished", "seq": 69,
                                            "payload": {"recordingId": REC, "operationId": None, "recording": completed,
                                                        "summary": dict(summary_available, actionCapture="COMPLETE")}},
})

# ---------------------------------------------------------------------------------------------- STUDIO-005: evidence/1
REC5 = "rec-3f1c2a9e-5b7d-4e21-9a0b-6c8d7e5f4a31"


def _ev5(type_, payload, seq=60):
    return {"protocol": P, "kind": "event", "type": type_, "seq": seq, "payload": payload}


def _cmd5(type_, payload, seq=70, id_="c-50"):
    return {"protocol": P, "kind": "command", "type": type_, "id": id_, "seq": seq, "payload": payload}


def _resp5(type_, payload, seq=71, id_="c-50"):
    return {"protocol": P, "kind": "response", "type": type_, "id": id_, "seq": seq, "ok": True, "payload": payload, "error": None}


def _evidence5(**over):
    e = {
        "recordingId": REC5,
        "evidenceId": "evd-1-4",
        "evidenceSequence": 4,
        "eventId": "ev-1-3",
        "stepSequence": 3,
        "observedAt": "2026-10-15T12:00:07.650Z",
        "type": "VISIBLE_ERROR_MESSAGE",
        "source": "FEEDBACK_OBSERVER",
        "relation": "AFTER_ACTION",
        "confidence": "TEMPORALLY_ASSOCIATED",
        "classification": "ERROR_INDICATOR",
        "document": {"frame": "MAIN", "inShadow": False, "title": "Login de teste", "url": "http://127.0.0.1:18765/lab/studio005/login-erro.html"},
        "details": {
            "region": "alerta do formulário login",
            "message": {"availability": "AVAILABLE", "text": "Usuário ou senha inválidos."},
            "field": None, "state": None, "navigation": None,
            "signals": ["ROLE_ALERT", "ERROR_HINT"], "candidates": 0, "signalsObserved": 0,
        },
        "limitations": [],
    }
    for k, v in over.items():
        if k in e["details"] and k not in ("message",):
            e["details"][k] = v
        else:
            e[k] = v
    return e


_EVSTATE5 = {"enabled": True, "lastSequence": 6, "ackedSequence": 4, "pending": 2, "lost": 0}
fixtures_valid["s005-command-start-evidence"] = _cmd5("studio.recording.start", {
    "recordingId": REC5, "operationId": "op-s5", "tabRef": "t-Q2f9LmZx7Kp1", "mode": "CAPTURE_AND_INTERACT", "leaseMs": 30000,
    "capabilities": ["steps/1", "evidence/1"], "routeRef": "route-1"})
fixtures_valid["s005-event-evidence-error"] = _ev5("studio.recording.evidence", _evidence5())
_e = _evidence5(evidenceId="evd-1-5", evidenceSequence=5, type="FORM_SUBMITTED", source="INTERACTION_NORMALIZER", relation="AT_ACTION",
                confidence="DIRECTLY_OBSERVED", classification=None, observedAt="2026-10-15T12:00:07.080Z")
_e["details"].update({"region": "formulário login", "message": {"availability": "NONE", "text": None}, "signals": ["SUBMIT_EVENT"]})
fixtures_valid["s005-event-evidence-form-submitted"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-6", evidenceSequence=6, type="VALIDATION_FEEDBACK", source="FORM_OBSERVER", relation="AT_ACTION",
                confidence="DIRECTLY_OBSERVED", classification="VALIDATION_INDICATOR")
_e["details"].update({"region": "formulário cadastro", "field": "E-mail", "message": {"availability": "SUPPRESSED", "text": None},
                      "signals": ["NATIVE_INVALID"]})
_e["limitations"] = ["MESSAGE_SUPPRESSED"]
fixtures_valid["s005-event-evidence-validation"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-7", evidenceSequence=7, eventId=None, stepSequence=None, type="AMBIGUOUS_FEEDBACK", confidence="AMBIGUOUS",
                classification="UNKNOWN_FEEDBACK")
_e["details"].update({"candidates": 2, "message": {"availability": "SUPPRESSED", "text": None}, "signals": ["ROLE_STATUS"]})
fixtures_valid["s005-event-evidence-ambiguous"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-8", evidenceSequence=8, type="ROUTE_CHANGED", source="NAVIGATION_OBSERVER", confidence="TEMPORALLY_ASSOCIATED",
                classification=None)
_e["details"].update({"region": None, "message": {"availability": "NONE", "text": None}, "signals": ["HISTORY_API"],
                      "navigation": {"kind": "SAME_DOCUMENT", "url": "http://127.0.0.1:18765/lab/studio005/spa.html#/clientes"}})
fixtures_valid["s005-event-evidence-route"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-9", evidenceSequence=9, type="ELEMENT_STATE_CHANGED", confidence="DIRECTLY_OBSERVED", classification=None)
_e["details"].update({"region": "botão Filtros", "message": {"availability": "NONE", "text": None}, "signals": ["TARGET_ATTRIBUTE"],
                      "state": {"attribute": "aria-expanded", "from": "false", "to": "true"}})
fixtures_valid["s005-event-evidence-state"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-10", evidenceSequence=10, type="OBSERVATION_INCOMPLETE", source="RECORDER", confidence="INCOMPLETE",
                classification=None)
_e["details"].update({"region": None, "message": {"availability": "NONE", "text": None}, "signals": [], "signalsObserved": 1})
_e["limitations"] = ["WINDOW_CUT_BY_PAUSE"]
fixtures_valid["s005-event-evidence-incomplete"] = _ev5("studio.recording.evidence", _e)
_e = _evidence5(evidenceId="evd-1-11", evidenceSequence=11, type="OBSERVATION_COMPLETE", confidence="DIRECTLY_OBSERVED", classification=None)
_e["document"].update({"frame": "FRAME", "inShadow": True, "title": None, "url": None})
_e["details"].update({"region": None, "message": {"availability": "NONE", "text": None}, "signals": [], "signalsObserved": 0})
fixtures_valid["s005-event-evidence-complete-frame"] = _ev5("studio.recording.evidence", _e)
fixtures_valid["s005-command-evidence-ack"] = _cmd5("studio.recording.evidence.ack", {"recordingId": REC5, "upToSequence": 4, "rejected": [2]})
fixtures_valid["s005-response-evidence-ack"] = _resp5("studio.recording.evidence.ack", {"recordingId": REC5, "evidence": _EVSTATE5})
fixtures_valid["s005-command-evidence-sync"] = _cmd5("studio.recording.evidence.sync", {"recordingId": REC5, "afterSequence": 4})
fixtures_valid["s005-response-evidence-sync"] = _resp5("studio.recording.evidence.sync", {"recordingId": REC5, "resent": 2, "evidence": _EVSTATE5})
fixtures_valid["s005-response-renew-evidence"] = _resp5("studio.recording.renew", {
    "recordingId": REC5, "state": "RECORDING", "expiresInMs": 30000,
    "steps": {"enabled": True, "lastSequence": 3, "ackedSequence": 3, "pending": 0, "lost": 0}, "evidence": _EVSTATE5})


def _bad5(name, mutate):
    e = _evidence5()
    mutate(e)
    fixtures_invalid[name] = _ev5("studio.recording.evidence", e)


_bad5("s005-evidence-with-html", lambda e: e["details"].update({"html": "<div role=alert>Usuário ou senha inválidos.</div>"}))
_bad5("s005-evidence-with-typed-value", lambda e: e["details"].update({"value": "anateste"}))
_bad5("s005-evidence-with-pagemap", lambda e: e.update({"pageMap": {"schemaVersion": "0.2.0", "nodes": []}}))
_bad5("s005-evidence-unknown-type", lambda e: e.update({"type": "BUSINESS_SUCCESS"}))
_bad5("s005-evidence-message-too-long", lambda e: e["details"].update({"message": {"availability": "AVAILABLE", "text": "x" * 161}}))
_bad5("s005-evidence-without-sequence", lambda e: e.pop("evidenceSequence"))
_bad5("s005-evidence-unknown-signal", lambda e: e["details"].update({"signals": ["CSS_CLASS_ERROR"]}))
_bad5("s005-evidence-confidence-percent", lambda e: e.update({"confidence": 0.8}))
_bad5("s005-evidence-business-outcome", lambda e: e.update({"businessOutcome": "SUCCESS"}))
fixtures_invalid["s005-start-unknown-evidence-capability"] = _cmd5("studio.recording.start", {
    "recordingId": REC5, "operationId": "op-s5", "tabRef": "t-Q2f9LmZx7Kp1", "mode": "CAPTURE_AND_INTERACT", "leaseMs": 30000,
    "capabilities": ["steps/1", "evidence/2"], "routeRef": "route-1"})
fixtures_invalid["s005-evidence-as-lifecycle-payload"] = _ev5("studio.recording.degraded", _evidence5())

for name, body in fixtures_valid.items():
    with open(os.path.join(valid, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(body, f, ensure_ascii=False, indent=2)
        f.write("\n")
for name, body in fixtures_invalid.items():
    with open(os.path.join(invalid, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
        json.dump(body, f, ensure_ascii=False, indent=2)
        f.write("\n")
print(len(fixtures_valid), "válidas,", len(fixtures_invalid), "inválidas")
