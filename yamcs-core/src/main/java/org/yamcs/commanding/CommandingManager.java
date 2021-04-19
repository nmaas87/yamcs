package org.yamcs.commanding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.ErrorInCommand;
import org.yamcs.Processor;
import org.yamcs.ValidationException;
import org.yamcs.YamcsException;
import org.yamcs.cmdhistory.CommandHistoryRequestManager;
import org.yamcs.management.ManagementService;
import org.yamcs.protobuf.Commanding.CommandHistoryAttribute;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.security.User;
import org.yamcs.xtce.ArgumentAssignment;
import org.yamcs.xtce.CommandVerifier;
import org.yamcs.xtce.DataSource;
import org.yamcs.xtce.MetaCommand;
import org.yamcs.xtce.Parameter;
import org.yamcs.xtce.TransmissionConstraint;
import org.yamcs.xtce.XtceDb;
import org.yamcs.xtceproc.MetaCommandProcessor;
import org.yamcs.xtceproc.MetaCommandProcessor.CommandBuildResult;

import com.google.common.util.concurrent.AbstractService;

/**
 * Responsible for parsing and tc packet composition.
 * <p>
 * Also keeps track of the pending active commands
 */
public class CommandingManager extends AbstractService {
    Logger log = LoggerFactory.getLogger(this.getClass().getName());
    final private Processor processor;
    final private CommandQueueManager commandQueueManager;
    final MetaCommandProcessor metaCommandProcessor;
    final private CommandHistoryRequestManager cmdHistoryManager;
    final CommandReleaser commandReleaser;

    /**
     * Keeps a reference to the channel and creates the queue manager
     * 
     * @param proc
     */
    public CommandingManager(Processor proc) throws ValidationException {
        this.processor = proc;
        this.commandQueueManager = new CommandQueueManager(this);
        ManagementService.getInstance().registerCommandQueueManager(proc.getInstance(), proc.getName(),
                commandQueueManager);
        metaCommandProcessor = new MetaCommandProcessor(proc.getProcessorData());
        cmdHistoryManager = proc.getCommandHistoryManager();
        this.commandReleaser = processor.getCommandReleaser();
    }

    public CommandQueueManager getCommandQueueManager() {
        return commandQueueManager;
    }

    /**
     * pc is a command whose source is included. parse the source populate the binary part and the definition.
     */
    public PreparedCommand buildCommand(MetaCommand mc, List<ArgumentAssignment> argAssignmentList, String origin,
            int seq, User user) throws ErrorInCommand, YamcsException {
        log.debug("Building command {} with arguments {}", mc.getName(), argAssignmentList);

        CommandBuildResult cbr = metaCommandProcessor.buildCommand(mc, argAssignmentList);

        CommandId cmdId = CommandId.newBuilder().setCommandName(mc.getQualifiedName()).setOrigin(origin)
                .setSequenceNumber(seq).setGenerationTime(processor.getCurrentTime()).build();
        PreparedCommand pc = new PreparedCommand(cmdId);
        pc.setMetaCommand(mc);
        pc.setBinary(cbr.getCmdPacket());
        pc.setUsername(user.getName());

        Set<String> userAssignedArgumentNames = argAssignmentList.stream()
                .map(a -> a.getArgumentName())
                .collect(Collectors.toSet());
        pc.setArgAssignment(cbr.getArgs(), userAssignedArgumentNames);

        return pc;
    }

    /**
     * @return the queue that the command was sent to
     */
    public CommandQueue sendCommand(User user, PreparedCommand pc) {
        log.debug("sendCommand commandSource={}", pc.getSource());
        ActiveCommand activeCommand = new ActiveCommand(processor, pc);
        cmdHistoryManager.addCommand(pc);
        cmdHistoryManager.subscribeCommand(pc.getCommandId(), activeCommand);
        return commandQueueManager.addCommand(user, activeCommand);
    }

    public void setCommandAttribute(CommandId commandId, CommandHistoryAttribute attribute) {
        commandQueueManager.addToCommandHistory(commandId, attribute);
    }

    public Processor getProcessor() {
        return processor;
    }

    public MetaCommandProcessor getMetaCommandProcessor() {
        return metaCommandProcessor;
    }

    @Override
    protected void doStart() {
        XtceDb mdb = processor.getXtceDb();

        Set<Parameter> paramsToSubscribe = new HashSet<>();
        for (MetaCommand mc : mdb.getMetaCommands()) {
            if (mc.hasTransmissionConstraints()) {
                List<TransmissionConstraint> tcList = mc.getTransmissionConstraintList();
                for (TransmissionConstraint tc : tcList) {
                    paramsToSubscribe.addAll(tc.getMatchCriteria().getDependentParameters());
                }
            }

            if (mc.hasCommandVerifiers()) {
                List<CommandVerifier> cvList = mc.getCommandVerifiers();
                for (CommandVerifier cv : cvList) {
                    paramsToSubscribe.addAll(cv.getDependentParameters());
                }
            }
        }
        paramsToSubscribe.removeIf(p -> p.getDataSource() == DataSource.COMMAND
                || p.getDataSource() == DataSource.COMMAND_HISTORY);

        if (!paramsToSubscribe.isEmpty()) {
            processor.getParameterProcessorManager().subscribeToProviders(paramsToSubscribe);
        } else {
            log.debug("No parameter required for post transmission contraint check");
        }
        commandQueueManager.startAsync();
        commandQueueManager.awaitRunning();
        notifyStarted();
    }

    @Override
    protected void doStop() {
        ManagementService.getInstance().unregisterCommandQueueManager(processor.getInstance(), processor.getName(),
                commandQueueManager);
        commandQueueManager.stopAsync();
        notifyStopped();
    }

    public void releaseCommand(ActiveCommand activeCommand) {
        // start the verifiers
        MetaCommand mc = activeCommand.getMetaCommand();
        if (mc.hasCommandVerifiers()) {
            log.debug("Starting command verification for {}", activeCommand);
            CommandVerificationHandler cvh = new CommandVerificationHandler(this, activeCommand);
            cvh.start();
        } else {
            cmdHistoryManager.unsubscribeCommand(activeCommand.getCommandId(), activeCommand);
        }

        commandReleaser.releaseCommand(activeCommand.getPreparedCommand());
    }

    public void failedCommand(ActiveCommand activeCommand) {
        cmdHistoryManager.unsubscribeCommand(activeCommand.getCommandId(), activeCommand);
    }

    public void unhandledCommand(ActiveCommand activeCommand) {
        cmdHistoryManager.unsubscribeCommand(activeCommand.getCommandId(), activeCommand);
    }

    public void verificatonFinished(ActiveCommand activeCommand) {
        cmdHistoryManager.unsubscribeCommand(activeCommand.getCommandId(), activeCommand);
    }
}
