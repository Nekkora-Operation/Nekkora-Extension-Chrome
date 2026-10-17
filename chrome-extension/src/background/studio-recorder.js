/*
 * Instância do Studio Recording Controller usada pelo service worker (dependências reais).
 * A lógica está em studio-recording.js (fábrica testável).
 */
import { client, host, toContent, tabKey, frameOfNode, broadcast, withLock, errorInfo } from './runtime.js';
import * as capture from './capture-session.js';
import * as policy from './access-policy.js';
import { createStudioRecorder } from './studio-recording.js';
import { currentCapture } from './inspection.js';

export const recorder = createStudioRecorder({ client, broadcast, withLock, errorInfo, capture, policy, host, toContent, tabKey, frameOfNode,
  currentCapture });
