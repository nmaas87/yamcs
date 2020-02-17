package org.yamcs.tctm;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.PreparedCommand;

import com.google.common.util.concurrent.RateLimiter;

/**
 * Abstract link that starts a thread when it's enabled and stops it when it's disabled.
 * <p>
 * The {@link #startUp()} and {@link #shutDown()} methods are called at startup/enable and shutdown/disable times on the working thread.
 * 
 */
public abstract class AbstractThreadedTcDataLink extends AbstractTcDataLink implements Runnable {
    Thread thread;
    RateLimiter rateLimiter;
    protected BlockingQueue<PreparedCommand> commandQueue;
    long initialDelay;

    public AbstractThreadedTcDataLink(String yamcsInstance, String linkName, YConfiguration config)
            throws ConfigurationException {
        super(yamcsInstance, linkName, config);
        if (config.containsKey("tcQueueSize")) {
            commandQueue = new LinkedBlockingQueue<>(config.getInt("tcQueueSize"));
        } else {
            commandQueue = new LinkedBlockingQueue<>();
        }

        initialDelay = config.getLong("initialDelay", 0);

        if (config.containsKey("tcMaxRate")) {
            rateLimiter = RateLimiter.create(config.getInt("tcMaxRate"));
        }

    }

    @Override
    protected void doStart() {
        if (!isDisabled()) {
            doEnable();
        }
        notifyStarted();
    }

    @Override
    protected void doStop() {
        if (!isDisabled()) {
            try {
                shutDown();
                commandQueue.clear();
                commandQueue.offer(SIGNAL_QUIT);
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for thread shutdown");
                    Thread.currentThread().interrupt();
                }
                notifyStopped();
            } catch (Exception e) {
                notifyFailed(e);
            }
        } else {
            notifyStopped();
        }
    }

    /**
     * Sends
     */
    @Override
    public void uplinkTc(PreparedCommand pc) {
        if (!commandQueue.offer(pc)) {
            log.warn("Cannot put command {} in the queue, because it's full; sending NACK", pc);
            commandHistoryPublisher.commandFailed(pc.getCommandId(), getCurrentTime(),
                    "Link " + linkName + ": queue full");
        }
    }

    @Override
    public void run() {
        if (initialDelay > 0) {
            try {
                Thread.sleep(initialDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            startUp();
        } catch (Exception e) {
            log.error("Failed to startUp", e);
        }

        while (isRunningAndEnabled()) {
            doHousekeeping();
            try {
                PreparedCommand pc = commandQueue.poll(housekeepingInterval, TimeUnit.MILLISECONDS);
                if (pc == null) {
                    continue;
                }
                if (pc == SIGNAL_QUIT) {
                    return;
                }

                if (rateLimiter != null) {
                    rateLimiter.acquire();
                }
                uplinkCommand(pc);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error when sending command: ", e);
                throw new RuntimeException(e);
            }
        }
        
        try {
            shutDown();
        } catch (Exception e) {
            log.error("Failed to shutDown", e);
            //TODO we should effectively fail the service here but we cannot because we already notified started
            // so we disable it instead
            disable();
        }
    }

    

    @Override
    protected void doEnable() {
        thread = new Thread(this);
        thread.start();
    }

    @Override
    protected void doDisable() {
        thread.interrupt();
    }

    /**
     * Called each {@link #housekeepingInterval} milliseconds, can be used to establish tcp connections or similar
     * things
     */
    protected void doHousekeeping() {
    }

    /**
     * Called 
     * @param pc
     * @throws IOException
     */
    protected abstract void uplinkCommand(PreparedCommand pc) throws IOException;


    /**
     * Called at start up (if the link is enabled) or when the link is enabled
     * @throws Exception
     */
    protected abstract void startUp() throws Exception;

    /**
     * Called at shutdown (if the link is enabled) or when the link is disabled
     * @throws Exception
     */
    protected abstract void shutDown() throws Exception;
}
