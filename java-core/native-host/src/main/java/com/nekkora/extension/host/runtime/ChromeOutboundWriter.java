package com.nekkora.extension.host.runtime;

import com.nekkora.extension.host.protocol.NativeMessageCodec;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ÚNICO escritor do stdout do Native Messaging (D-STUDIO-002-05).
 *
 * <p>Cada envio é uma <b>transmissão</b>: a lista completa de quadros de uma mensagem (um quadro, ou todos os
 * fragmentos {@code chunk} de uma resposta grande). Somente a thread do escritor toca o fluxo, e ela escreve cada
 * transmissão inteira antes de olhar a próxima — então nenhum envelope (evento do Studio, outra resposta) pode cair
 * entre dois fragmentos da mesma mensagem. Sincronizar {@code write()} por quadro não bastaria.
 *
 * <p>Duas filas: CONTROLE (comandos/avisos do Studio, respostas de controle) tem precedência sobre MOTOR (respostas
 * do motor de análise). A precedência só vale ENTRE transmissões; uma transmissão iniciada nunca é interrompida.
 */
public final class ChromeOutboundWriter implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ChromeOutboundWriter.class.getName());

    /** Fila de destino. */
    public enum Lane { CONTROL, ENGINE }

    private final NativeMessageCodec codec;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();
    private final Condition drained = lock.newCondition();
    private final Deque<List<String>> control = new ArrayDeque<>();
    private final Deque<List<String>> engine = new ArrayDeque<>();
    private final Thread thread;
    private volatile boolean closed;
    private volatile boolean failed;
    private int inFlight;
    private long transmissions;

    public ChromeOutboundWriter(NativeMessageCodec codec) {
        this.codec = codec;
        this.thread = new Thread(this::run, "nekkora-chrome-writer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Enfileira uma transmissão (todos os quadros serão escritos contiguamente). */
    public void submit(List<String> frames, Lane lane) {
        if (frames == null || frames.isEmpty() || closed) {
            return;
        }
        lock.lock();
        try {
            (lane == Lane.CONTROL ? control : engine).addLast(List.copyOf(frames));
            available.signal();
        } finally {
            lock.unlock();
        }
    }

    private void run() {
        while (true) {
            List<String> next;
            lock.lock();
            try {
                while (control.isEmpty() && engine.isEmpty() && !closed) {
                    available.awaitUninterruptibly();
                }
                if (control.isEmpty() && engine.isEmpty()) {
                    drained.signalAll();
                    return;
                }
                next = !control.isEmpty() ? control.pollFirst() : engine.pollFirst();
                inFlight++;
            } finally {
                lock.unlock();
            }
            try {
                if (!failed) {
                    for (String frame : next) {
                        codec.write(frame); // a transmissão inteira, sem ceder a vez
                    }
                }
            } catch (IOException e) {
                failed = true; // Chrome fechou a porta: o leitor verá o fim do fluxo
                LOG.log(Level.WARNING, "Falha ao escrever para o Chrome", e);
            } finally {
                lock.lock();
                try {
                    inFlight--;
                    transmissions++;
                    if (control.isEmpty() && engine.isEmpty() && inFlight == 0) {
                        drained.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /** Aguarda o esvaziamento das filas (usado no encerramento e nos testes). */
    public boolean awaitDrained(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        lock.lock();
        try {
            while (!(control.isEmpty() && engine.isEmpty() && inFlight == 0)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                drained.awaitNanos(left);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    public long transmissions() {
        lock.lock();
        try {
            return transmissions;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            available.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
