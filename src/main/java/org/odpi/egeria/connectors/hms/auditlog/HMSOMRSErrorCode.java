/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.hms.auditlog;

import org.odpi.openmetadata.frameworks.auditlog.messagesets.ExceptionMessageDefinition;
import org.odpi.openmetadata.frameworks.auditlog.messagesets.ExceptionMessageSet;

public enum HMSOMRSErrorCode implements ExceptionMessageSet {

    ENDPOINT_NOT_SUPPLIED_IN_CONFIG(400, "OMRS-HMS-REPOSITORY-400-001 ",
                                  "The endpoint was not supplied in the connector configuration \"{1}\"",
                                  "Connector unable to continue",
                                  "Supply a valid thrift end point in the configuration endpoint."),
    FAILED_TO_START_CONNECTOR(400, "OMRS-HMS-REPOSITORY-400-002 ",
                                   "The Hive metastore connector failed to start",
                                   "Connector is unable to be used",
                                   "Review your configuration to ensure it is valid."),

    NO_CATALOGS_EXCEPTION(400, "OMRS-HMS-REPOSITORY-400-003 ",
            "getCatalogs call was issued to HMS and failed",
            "Connector is unable to be used. as there is no content to sync",
            "ensure that the HMS you are working with supports getCatalogs API. Alternatively specify catalogName in the configuration with the catalog you require. "),

    TYPE_ERROR_EXCEPTION(400, "OMRS-HMS-REPOSITORY-400-004 ",
                         "Type error exception",
                         "Connector is unable to be used",
                         "Review the configuration. Check the logs and debug."),

    EVENT_MAPPER_IMPROPERLY_INITIALIZED(400, "OMRS-HMS-REPOSITORY-400-005 ",
                                        "The event mapper has been improperly initialized for repository {0}",
                                        "The system will be unable to process any events",
                                        "Check the system logs and diagnose or report the problem."),

    ENCODING_EXCEPTION(400, "OMRS-HMS-REPOSITORY-400-006 ",
                                          "The event mapper failed to encode '{0}' with value '{1}' to create a guid",
                                          "The system will shutdown the server",
                                          "Debug the cause of the encoding error."),
    EVENT_MAPPER_CANNOT_GET_TYPES(400, "OMRS-HMS-REPOSITORY-400-007 ",
                                  "The event mapper failed to obtain the types, so cannot proceed ",
                                  "The system will shutdown the server",
                                  "Ensure you are using a repository that supports the required types."),

    CONFIG_ERROR_CONNECTION_SECURED_PROPERTIES(400, "OMRS-HMS-REPOSITORY-400-008 ",
            "The connector configuration connectionSecuredProperties in not correct as is does not cast to a String of Strings.",
            "The system will shutdown the server",
            "Remove or correct the connectionSecuredProperties. It needs to be a map of Strings."),

    FAILED_TO_GET_COLUMNS_FOR_EXTERNAL_TABLE(400, "OMRS-HMS-REPOSITORY-400-009 ",
            "The Hive metastore connector failed accessing the HMS table parameters for external table {0}",
            "Connector is unable to be used",
            "Investigate the external table."),
    NEED_NAMED_CATALOG_IN_CONFIG(400, "OMRS-HMS-REPOSITORY-400-009 ",
            "The connector configuration incorrectly contains useSSL=true and a specified database name, but no catalog name.",
            "The system will shutdown the server",
            "Amend the configuration to supply a valid catalogName to work with."),

    TYPEDEF_NAME_NOT_KNOWN(404, "OMRS-HMS-REPOSITORY-404-001",
                           "On Server {0} for request {1}, the TypeDef unique name {2} passed is not known to this repository connector",
                           "The system is unable to retrieve the properties for the requested TypeDef because the supplied identifiers are not recognized.",
                           "The identifier is supplied by the caller.  It may have a logic problem that has corrupted the identifier, or the TypeDef has been deleted since the identifier was retrieved.")

    ;

    final private ExceptionMessageDefinition messageDefinition;

    /**
     * The constructor for HMSOMRSErrorCode expects to be passed one of the enumeration rows defined in
     * HMSOMRSErrorCode above.   For example:
     *
     *     HMSOMRSErrorCode   errorCode = HMSOMRSErrorCode.NULL_INSTANCE;
     *
     * This will expand out to the 5 parameters shown below.
     *
     * @param newHTTPErrorCode - error code to use over REST calls
     * @param newErrorMessageId - unique Id for the message
     * @param newErrorMessage - text for the message
     * @param newSystemAction - description of the action taken by the system when the error condition happened
     * @param newUserAction - instructions for resolving the error
     */
    HMSOMRSErrorCode(int newHTTPErrorCode, String newErrorMessageId, String newErrorMessage, String newSystemAction, String newUserAction) {
        this.messageDefinition = new ExceptionMessageDefinition(newHTTPErrorCode,
                newErrorMessageId,
                newErrorMessage,
                newSystemAction,
                newUserAction);
    }

    /**
     * Retrieve a message definition object for an exception.  This method is used when there are no message inserts.
     *
     * @return message definition object.
     */
    @Override
    public ExceptionMessageDefinition getMessageDefinition() {
        return messageDefinition;
    }


    /**
     * Retrieve a message definition object for an exception.  This method is used when there are values to be inserted into the message.
     *
     * @param params array of parameters (all strings).  They are inserted into the message according to the numbering in the message text.
     * @return message definition object.
     */
    @Override
    public ExceptionMessageDefinition getMessageDefinition(String... params) {
        messageDefinition.setMessageParameters(params);
        return messageDefinition;
    }

    /**
     * toString() JSON-style
     *
     * @return string description
     */
    @Override
    public String toString() {
        return "HMSOMRSErrorCode{" +
                "messageDefinition=" + messageDefinition +
                '}';
    }

}
