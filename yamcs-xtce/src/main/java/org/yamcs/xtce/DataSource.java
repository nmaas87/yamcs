package org.yamcs.xtce;

/**
 * The data source is associated to a {@link Parameter} and specifies the source of the values for that parameter.
 * 
 * @author nm
 *
 */
public enum DataSource {
    /**
     * used for data acquired from outside, parameters of this type cannot be changed
     */
    TELEMETERED,
    /**
     * parameters set by the algorithm manager
     */
    DERIVED,
    /**
     * constants in the XtceDb - cannot be changed
     */
    CONSTANT,
    /**
     * software parameters maintained by Yamcs and that can be set by client
     */
    LOCAL,
    /**
     * parameters giving internal yamcs state -created on the fly
     */
    SYSTEM,
    /**
     * parameters used in the context of command verifiers
     */
    COMMAND,
    /**
     * special parameters created on the fly and instantiated in the context of command verifiers
     */
    COMMAND_HISTORY,
    /**
     * external parameters are like local parameters (can be set by the client) but maintained outside Yamcs.
     * These are project specific and require a <code>SoftwareParameterManager</code> to be defined in the Yamcs
     * processor configuration.
     * 
     */
    EXTERNAL1, EXTERNAL2, EXTERNAL3;
}
