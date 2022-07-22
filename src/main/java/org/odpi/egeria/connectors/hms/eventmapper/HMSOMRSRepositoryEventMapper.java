/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.egeria.connectors.hms.eventmapper;


import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Table;
import org.odpi.egeria.connectors.hms.auditlog.HMSOMRSAuditCode;
import org.odpi.egeria.connectors.hms.auditlog.HMSOMRSErrorCode;
import org.odpi.egeria.connectors.hms.repositoryconnector.CachingOMRSRepositoryProxyConnector;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.frameworks.connectors.properties.EndpointProperties;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.OMRSMetadataCollection;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.*;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.PrimitiveDefCategory;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDef;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.typedefs.TypeDefSummary;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.repositoryeventmapper.OMRSRepositoryEventMapperBase;
import org.odpi.openmetadata.repositoryservices.ffdc.exception.*;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

//import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.thrift.TException;

import java.util.Arrays;
import java.util.List;


/**
 * HMSOMRSRepositoryEventMapper supports the event mapper function for a Hive metastore
 * when used as an open metadata repository.
 *
 * This class is an implementation of an OMRS event mapper, it polls for content in Hive metastore and puts
 * that content into an embedded Egeria repository. It then (if configured to send batch events) extracts the content
 * from the embedded repository and sends as batched events.
 *
 */
public class HMSOMRSRepositoryEventMapper extends OMRSRepositoryEventMapperBase
//        implements OpenMetadataTopicListener
{

    /*
     *    Open Type names
     */
    private static final String CONNECTION = "Connection";
    private static final String CONNECTOR_TYPE = "ConnectorType";
    private static final String ENDPOINT = "Endpoint";
    private static final String CONNECTION_ENDPOINT = "ConnectionEndpoint";
    private static final String CONNECTION_CONNECTOR_TYPE = "ConnectionConnectorType";
    private static final String CONNECTION_TO_ASSET = "ConnectionToAsset";

    private static final String DATABASE = "Database";
    private static final String  DEPLOYED_DATABASE_SCHEMA = "DeployedDatabaseSchema";
    private static final String  RELATIONAL_DB_SCHEMA_TYPE = "RelationalDBSchemaType";
    // relationship
    private static final String  DATA_CONTENT_FOR_DATASET = "DataContentForDataSet";
    // relationship
    private static final String ASSET_SCHEMA_TYPE =  "AssetSchemaType";
    //relationship
    private static final String ATTRIBUTE_FOR_SCHEMA =  "AttributeForSchema";

    private static final String TABLE = "RelationalTable";

    private static final String COLUMN = "RelationalColumn";
    // relationship
    private static final String NESTED_SCHEMA_ATTRIBUTE = "NestedSchemaAttribute";
    // classification
    private static final String TYPE_EMBEDDED_ATTRIBUTE = "TypeEmbeddedAttribute";
    private static final String RELATIONAL_TABLE_TYPE = "RelationalTableType";
    private static final String RELATIONAL_COLUMN_TYPE = "RelationalColumnType";

    /**
     * Running field is a thread safe indicator that the thread is running. So stop the thread set the running flag to false.
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * map to help manage type guids, by keeping a map for type name to guid.
     */
    private Map<String, String> typeNameToGuidMap = null;

    /**
     * UserId associated with this connector
     */
    private String userId = null;
    /**
     * Default polling refresh interval in milliseconds.
     */
    private int refreshInterval = 5000;
    private String qualifiedNamePrefix = "";
    protected String metadataCollectionId = null;
    protected String metadataCollectionName = null;
    protected OMRSMetadataCollection metadataCollection = null;

    private String repositoryName = null;

    final List<String> supportedTypeNames = Arrays.asList(new String[]{
            // entity types
            "Asset", // super type of Database
            "Referenceable", // super type of the others
            "OpenMetadataRoot", // super type of referenceable
            CONNECTION,
            CONNECTOR_TYPE,
            ENDPOINT,
            // relationship types
            CONNECTION_ENDPOINT,
            CONNECTION_CONNECTOR_TYPE,
            CONNECTION_TO_ASSET,
            DATABASE,
            RELATIONAL_DB_SCHEMA_TYPE,
            TABLE,
            COLUMN,
            NESTED_SCHEMA_ATTRIBUTE,
            ATTRIBUTE_FOR_SCHEMA,
            "SchemaAttribute",
            "SchemaElement",
            "ComplexSchemaType",
            "SchemaType",

            RELATIONAL_TABLE_TYPE ,
            RELATIONAL_COLUMN_TYPE,

            // classification types
            TYPE_EMBEDDED_ATTRIBUTE,
            });

    private String catName = "spark";
    private String dbName = "default";
    private String metadata_store_userId = null;
    private String metadata_store_password = null;

    private boolean sendPollEvents = false;

    private boolean useSSL = false;

    private String  configuredEndpointAddress = null;

    private PollingThread pollingThread;
    private String databaseGUID;

    /**
     * Default constructor
     */
    public HMSOMRSRepositoryEventMapper() {
        super();
//        this.sourceName = "HMSOMRSRepositoryEventMapper";
    }

    private HiveMetaStoreClient connectToHMS() throws TException,RepositoryErrorException {
        String methodName = "connectToHMS";
        HiveMetaStoreClient client = null;
        EndpointProperties endpointProperties = connectionProperties.getEndpoint();
        if (endpointProperties == null) {
            raiseRepositoryErrorException(HMSOMRSErrorCode.ENDPOINT_NOT_SUPPLIED_IN_CONFIG, methodName, null, "null");
        } else {
            // populate the Hive configuration for the HMS client.
            Configuration conf = new Configuration();
            // we only support one thrift uri at this time
            conf.set("metastore.thrift.uris", endpointProperties.getAddress());
            if (useSSL) {
                conf.set("metastore.use.SSL", "true");
                conf.set("metastore.truststore.path", "file:///" + System.getProperty("java.home") + "/lib/security/cacerts");
                conf.set("metastore.truststore.password", "changeit");
                conf.set("metastore.client.auth.mode", "PLAIN");
                conf.set("metastore.client.plain.username", metadata_store_userId);
                conf.set("metastore.client.plain.password", metadata_store_password);
            }


            client = new HiveMetaStoreClient(conf, null, false);
            metadataCollection = this.repositoryConnector.getMetadataCollection();
            if (this.userId == null) {
                // default
                this.userId = "OMAGServer";
            }
            metadataCollectionId = metadataCollection.getMetadataCollectionId(this.userId);
        }
        return client;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void start() throws ConnectorCheckedException {

        super.start();

        final String methodName = "start";
        repositoryName = this.repositoryConnector.getRepositoryName();
        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_STARTING.getMessageDefinition());

        if (!(repositoryConnector instanceof CachingOMRSRepositoryProxyConnector)) {
            raiseConnectorCheckedException(HMSOMRSErrorCode.EVENT_MAPPER_IMPROPERLY_INITIALIZED, methodName, null, repositoryConnector.getServerName());
        }

        this.repositoryHelper = this.repositoryConnector.getRepositoryHelper();

        Map<String, Object> configurationProperties = connectionProperties.getConfigurationProperties();
        this.userId = connectionProperties.getUserId();
        if (this.userId == null) {
            // default
            this.userId = "OMAGServer";
        }
        if (configurationProperties != null) {
            extractConfigurationProperties(configurationProperties);

        }
        if (metadataCollection == null) {
            try {
                connectToHMS();
            } catch (RepositoryErrorException | TException cause) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.FAILED_TO_START_CONNECTOR, methodName, null);
            }
        }

        this.pollingThread = new PollingThread();
        pollingThread.start();
    }

    /**
     * Extract Egeria configuration properties into instance variables
     * @param configurationProperties map of Egeria configuration variables.
     */
    private void extractConfigurationProperties(Map<String, Object> configurationProperties) {
        Integer configuredRefreshInterval = (Integer) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.REFRESH_TIME_INTERVAL);
        if (configuredRefreshInterval != null) {
            refreshInterval = configuredRefreshInterval * 1000;
        }
        String configuredQualifiedNamePrefix = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.QUALIFIED_NAME_PREFIX);
        if (configuredQualifiedNamePrefix != null) {
            qualifiedNamePrefix = configuredQualifiedNamePrefix;
        }
        String configuredCatName = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.CATALOG_NAME);
        if (configuredCatName != null) {
            catName = configuredCatName;
        }
        String configuredDBName = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.DATABASE_NAME);
        if (configuredDBName != null) {
            dbName = configuredDBName;
        }
        String configuredMetadataStoreUserId = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.METADATA_STORE_USER);
        if (configuredMetadataStoreUserId != null) {
            metadata_store_userId = configuredMetadataStoreUserId;
        }
        String configuredMetadataStorePassword = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.METADATA_STORE_PASSWORD);
        if (configuredMetadataStorePassword != null) {
            metadata_store_password = configuredMetadataStorePassword;
        }
//            String configuredThriftURL = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.THRIFT_URL);
//            if (configuredMetadataStorePassword != null) {
//               thrift_url = configuredThriftURL;
//            }
        Boolean configuredSendPollEvents = (Boolean) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.SEND_POLL_EVENTS);
        if (configuredSendPollEvents != null) {
            sendPollEvents = configuredSendPollEvents;
        }
        Boolean configuredSUseSSL = (Boolean) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.USE_SSL);
        if (configuredSUseSSL != null) {
            useSSL = configuredSUseSSL;
        }
        configuredEndpointAddress = (String) configurationProperties.get(HMSOMRSRepositoryEventMapperProvider.ENDPOINT_ADDRESS_PREFIX);
    }


    /**
     * Class to poll for file content
     */
    private class PollingThread implements Runnable {
        Thread worker = null;
        void start() {
            Thread worker = new Thread(this);
            worker.start();
        }

        void stop() {
            if (!running.compareAndSet(true, false)) {
                auditLog.logMessage("stop", HMSOMRSAuditCode.POLLING_THREAD_INFO_ALREADY_STOPPED.getMessageDefinition());
            }
        }

        private List<Relationship> getRelationshipsForEntityHelper(
                String entityGUID,
                String relationshipTypeGUID) throws ConnectorCheckedException {
            String methodName = "getRelationshipsForEntityHelper";
            List<Relationship> relationships = null;
            try {
                relationships = metadataCollection.getRelationshipsForEntity(userId, entityGUID, relationshipTypeGUID, 0, null, null, null, null, 0);
            } catch (InvalidParameterException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_PARAMETER_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (RepositoryErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.REPOSITORY_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (PropertyErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.PROPERTY_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (PagingErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.PAGING_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (FunctionNotSupportedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.FUNCTION_NOT_SUPPORTED_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (UserNotAuthorizedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.USER_NOT_AUTHORIZED_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (EntityNotKnownException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENTITY_NOT_KNOWN, methodName, e, repositoryConnector.getServerName(), methodName, entityGUID);
            }
            return relationships;
        }

        private EntityDetail getEntityDetail(String guid) throws ConnectorCheckedException {
            String methodName = "getEntityDetail";
            EntityDetail entityDetail = null;
            try {
                entityDetail = metadataCollection.getEntityDetail(userId, guid);
            } catch (InvalidParameterException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_PARAMETER_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (RepositoryErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.REPOSITORY_ERROR_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (UserNotAuthorizedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.USER_NOT_AUTHORIZED_EXCEPTION, methodName, e, repositoryConnector.getServerName(), methodName);
            } catch (EntityNotKnownException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENTITY_NOT_KNOWN, methodName, e, repositoryConnector.getServerName(), methodName, guid);
            } catch (EntityProxyOnlyException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENTITY_PROXY_ONLY, methodName, e, repositoryConnector.getServerName(), methodName, guid);
            }
            return entityDetail;


        }

        void sendBatchEvent() throws ConnectorCheckedException {
            EntityDetail databaseEntity = getEntityDetail(databaseGUID);
            List<Relationship> relationshipList = new ArrayList<>();
            List<EntityDetail> entityList = new ArrayList<>();
            entityList.add(databaseEntity);

            List<String> connectionGuids = updateRelationshipAndEntityLists(CONNECTION_TO_ASSET, databaseGUID, entityList, relationshipList);
            if (connectionGuids != null && connectionGuids.size() > 0) {
                for (String connectionGUID : connectionGuids) {
                    updateRelationshipAndEntityLists(CONNECTION_CONNECTOR_TYPE, connectionGUID, entityList, relationshipList);
                    updateRelationshipAndEntityLists(CONNECTION_ENDPOINT, connectionGUID, entityList, relationshipList);
                }
            }
            List<String> deployedDatabaseSchemaGuids = updateRelationshipAndEntityLists(ASSET_SCHEMA_TYPE, databaseGUID, entityList, relationshipList);

            if (deployedDatabaseSchemaGuids != null && deployedDatabaseSchemaGuids.size() > 0) {
                for (String deployedDatabaseSchemaGuid : deployedDatabaseSchemaGuids) {
                    List<String> relationalDBSchemaTypeGuids = updateRelationshipAndEntityLists(DATA_CONTENT_FOR_DATASET, deployedDatabaseSchemaGuid, entityList, relationshipList);
                    issueBatchEvent(relationshipList, entityList);

                    if (relationalDBSchemaTypeGuids != null && relationalDBSchemaTypeGuids.size() > 0) {
                        for (String relationalDBSchemaTypeGuid : relationalDBSchemaTypeGuids) {
                            /* for each relationalTable send a separate batch event with
                             *  - relationship to relationalTable (ATTRIBUTE_FOR_SCHEMA)
                             *  - relational table
                             *  - relationships to columns (NESTED_SCHEMA_ATTRIBUTE)
                             *  - relationalColumns
                             */
                            List<Relationship> tableRelationshipList = new ArrayList<>();
                            List<EntityDetail> tableEntityList = new ArrayList<>();
                            List<String> relationalTableGuids = updateRelationshipAndEntityLists(ATTRIBUTE_FOR_SCHEMA, relationalDBSchemaTypeGuid, tableEntityList, tableRelationshipList);
                            if (relationalTableGuids != null && relationalTableGuids.size() > 0) {
                                for (String relationalTableGuid : relationalTableGuids) {
                                    updateRelationshipAndEntityLists(NESTED_SCHEMA_ATTRIBUTE, relationalTableGuid, tableEntityList, tableRelationshipList);
                                }
                            }
                            issueBatchEvent(tableRelationshipList, tableEntityList);
                        }
                    }
                }
            }
        }

        private void issueBatchEvent(List<Relationship> relationshipList, List<EntityDetail> entityList) {
            InstanceGraph instances = new InstanceGraph(entityList, relationshipList);

            // send the event
            repositoryEventProcessor.processInstanceBatchEvent("HMSOMRSRepositoryEventMapper",
                                                               repositoryConnector.getMetadataCollectionId(),
                                                               repositoryConnector.getServerName(),
                                                               repositoryConnector.getServerType(),
                                                               repositoryConnector.getOrganizationName(),
                                                               instances);
        }

        /**
         * This method is passed an entity guid and a relationship type name, it gets the relationships based on the name and
         * entity guid. A list of relationships is found, for each relationship we add the relationship to the
         * relationship list and the entity at the other end to the entity list.
         *
         * @param relationshipTypeName - type of the relationships or entity
         * @param startEntityGUID - entity guid of the end we know
         * @param entityList - the list of entities to update
         * @param relationshipList - the list of relationships to update
         * @return a list of the guids of the other end entities
         * @throws ConnectorCheckedException error getting the relationships
         */
        private List<String> updateRelationshipAndEntityLists(String relationshipTypeName, String startEntityGUID, List<EntityDetail> entityList, List<Relationship> relationshipList) throws ConnectorCheckedException {
            String methodName = "updateRelationshipAndEntityLists";

            List<String> otherEndGuids = new ArrayList<>();
            TypeDefSummary typeDefSummary = repositoryHelper.getTypeDefByName(methodName, relationshipTypeName);
            String relationshipTypeGUID = typeDefSummary.getGUID();
            List<Relationship> connectorConnectorTypeRelationships = getRelationshipsForEntityHelper(startEntityGUID, relationshipTypeGUID);
            for (Relationship relationship : connectorConnectorTypeRelationships) {
                EntityProxy proxy = repositoryHelper.getOtherEnd(methodName,
                                                                 startEntityGUID,
                                                                 relationship);
                String guid = proxy.getGUID();
                EntityDetail otherEndEntity = getEntityDetail(guid);
                entityList.add(otherEndEntity);
                relationshipList.add(relationship);
                otherEndGuids.add(otherEndEntity.getGUID());
            }
            return otherEndGuids;

        }


        @Override
        public void run() {

            final String methodName = "run";
            if (running.compareAndSet(false, true)) {
                while (running.get()) {
                    try {
                        getRequiredTypes();
                        // call the repository connector to refresh its contents.
                        refreshRepository();
                        // send the batch event per asset
                        if (sendPollEvents) {
                            sendBatchEvent();
                        }
                        //  wait the polling interval.
                        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_PRE_WAIT.getMessageDefinition());
                        try {
                            Thread.sleep(refreshInterval);
                            auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_POST_WAIT.getMessageDefinition());
                        } catch (InterruptedException e) {
                            // should not happen as there is only one thread
                            // if it happens then continue in the while
                            auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_INTERRUPTED_EXCEPTION.getMessageDefinition());
                        }


                    } catch (ConnectorCheckedException e) {
                        if (e.getCause() == null) {
                            auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_GOT_AN_EXCEPTION.getMessageDefinition(e.getMessage()));
                        } else {
                            auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_GOT_AN_EXCEPTION_WITH_CAUSE.getMessageDefinition(e.getMessage(), e.getCause().getMessage()));
                        }
                    } catch (Exception e) {
                        // catch everything else
                        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_POLL_LOOP_GOT_AN_EXCEPTION_WITH_CAUSE.getMessageDefinition(e.getMessage(), e.getCause().getMessage()));
                    } finally {
                        // stop the thread if we came out of the loop.
                        this.stop();
                    }
                }
            }
        }



        private void getRequiredTypes() throws ConnectorCheckedException {
            String methodName = "getRequiredTypes";
            final int supportedCount = supportedTypeNames.size();

            int typesAvailableCount = 0;
            int retryCount = 0;
            while ((typesAvailableCount != supportedCount)) {
                auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_ACQUIRING_TYPES_LOOP.getMessageDefinition(typesAvailableCount + "", supportedCount + "", retryCount + ""));
                // only come out the while loop when we can get all of the supported types in one iteration.
                typesAvailableCount = 0;
                if (typeNameToGuidMap == null) {
                    typeNameToGuidMap = new HashMap<>();
                }
                // populate the type name to guid map
                for (String typeName : supportedTypeNames) {

                    TypeDef typeDef = repositoryHelper.getTypeDefByName("HMSOMRSRepositoryEventMapper",
                                                                        typeName);
                    if (typeDef != null) {
                        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_ACQUIRING_TYPES_LOOP_FOUND_TYPE.getMessageDefinition(typeName));
                        typeNameToGuidMap.put(typeName, typeDef.getGUID());
                        typesAvailableCount++;
                    }
                }
                if (typesAvailableCount < supportedCount) {
                    //delay for 1 second and then retry

                    try {
                        Thread.sleep(1000);  // TODO Should this be in configuration?
                        retryCount++;
                    } catch (InterruptedException e) {
                        // should not happen as there is only one thread
                        // if it does happen it would result in a lower duration for the sleep
                        //
                        // Increment the retry count, in case this happens everytime
                        retryCount++;
                        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_ACQUIRING_TYPES_LOOP_INTERRUPTED_EXCEPTION.getMessageDefinition());
                    }
                } else if (typesAvailableCount == supportedCount) {
                    // log to say we have all the types we need
                    auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_ACQUIRED_ALL_TYPES.getMessageDefinition());

                }

                if (retryCount == 20) { // TODO  Should this be in configuration?
                    raiseConnectorCheckedException(HMSOMRSErrorCode.EVENT_MAPPER_CANNOT_GET_TYPES, methodName, null);
                }
            }
        }
        public void refreshRepository() throws ConnectorCheckedException {
            String methodName = "refreshRepository";
            HiveMetaStoreClient client = null;
            try {
                try {
                    client = connectToHMS();
                } catch (RepositoryErrorException cause) {
                    raiseConnectorCheckedException(HMSOMRSErrorCode.FAILED_TO_START_CONNECTOR, methodName, null);
                }
                // Create database
                String baseCanonicalName = catName+"::"+dbName;
                EntityDetail databaseEntity = getEntityDetailSkeleton(methodName,
                                                                      DATABASE,
                                                                      dbName,
                                                                      baseCanonicalName,
                                                                      null,
                                                                      false);
                databaseGUID = databaseEntity.getGUID();
                issueSaveEntityReferenceCopy(databaseEntity);

                createConnectionOrientatedEntities( baseCanonicalName, databaseEntity);

                // create DeployedDatabaseSchema
                EntityDetail deployedDatabaseSchemaEntity = getEntityDetailSkeleton(methodName,
                                                                                    DEPLOYED_DATABASE_SCHEMA,
                                                                                    dbName+"_schema",
                                                                                    baseCanonicalName+"_schema",
                                                                                    null,
                                                                                    false);
                issueSaveEntityReferenceCopy(deployedDatabaseSchemaEntity);
                // create RelationalDBType
                EntityDetail relationalDBTypeEntity = getEntityDetailSkeleton(methodName,
                                                                              RELATIONAL_DB_SCHEMA_TYPE,
                                                                              dbName+"_schemaType",
                                                                              baseCanonicalName+"_schemaType",
                                                                              null,
                                                                              false);
                issueSaveEntityReferenceCopy(relationalDBTypeEntity);

                // create Relationships
                String databaseGuid = databaseEntity.getGUID();
                String deployedDatabaseSchemaGuid = deployedDatabaseSchemaEntity.getGUID();
                String relationalDBTypeGuid = relationalDBTypeEntity.getGUID();
                // create the 2 relationships
                createReferenceRelationship(ASSET_SCHEMA_TYPE,
                                            databaseGuid,
                                            DATABASE,
                                            deployedDatabaseSchemaGuid,
                                            DEPLOYED_DATABASE_SCHEMA);

                createReferenceRelationship(DATA_CONTENT_FOR_DATASET,
                                            deployedDatabaseSchemaGuid,
                                            DEPLOYED_DATABASE_SCHEMA,
                                            relationalDBTypeGuid,
                                            RELATIONAL_DB_SCHEMA_TYPE);

                List<String> tables = client.getTables(catName, dbName, "*");

                if (tables !=null && !tables.isEmpty()) {
                    // create each table and relationship
                    for (String tableName : tables) {
                        Table table = client.getTable(catName, dbName, tableName);
                        String tableType = table.getTableType();
                        if (tableType != null && tableType.equals("EXTERNAL_TABLE")) {
                            String tableCanonicalName = baseCanonicalName + "_schema_" + tableName;
                            EntityDetail tableEntity = getEntityDetailSkeleton(methodName,
                                                                               TABLE,
                                                                               tableName,
                                                                               tableCanonicalName,
                                                                               null,
                                                                               true);
//                            String owner = table.getOwner();
//                            if (owner != null) {
//                                tableEntity.setOwner(owner);
//                            }
                            int createTime = table.getCreateTime();
                            tableEntity.setCreateTime(new Date(createTime));


                            List<Classification> tableClassifications = tableEntity.getClassifications();
                            if (tableClassifications == null) {
                                tableClassifications = new ArrayList<>();
                            }
                            Classification classification = createTypeEmbeddedClassificationForTable(methodName, tableEntity);
                            tableClassifications.add(classification);
                            tableEntity.setClassifications(tableClassifications);

                            issueSaveEntityReferenceCopy(tableEntity);
                            String tableGuid = tableEntity.getGUID();
                            // relationship


                            createReferenceRelationship(ATTRIBUTE_FOR_SCHEMA,
                                                        relationalDBTypeGuid,
                                                        RELATIONAL_DB_SCHEMA_TYPE,
                                                        tableGuid,
                                                        TABLE);

                            Iterator<FieldSchema> colsIterator = table.getSd().getColsIterator();

                            while (colsIterator.hasNext()) {
                                FieldSchema fieldSchema = colsIterator.next();
                                String columnName = fieldSchema.getName();

                                EntityDetail columnEntity = getEntityDetailSkeleton(methodName,
                                                                                    COLUMN,
                                                                                    columnName,
                                                                                    tableCanonicalName + "_" + columnName,
                                                                                    null,
                                                                                    true);
                                String dataType = fieldSchema.getType();

                                List<Classification> columnClassifications = columnEntity.getClassifications();
                                if (columnClassifications == null) {
                                    columnClassifications = new ArrayList();
                                }
                                columnClassifications.add(createTypeEmbeddedClassificationForColumn("refreshRepository", columnEntity, dataType));
                                columnEntity.setClassifications(columnClassifications);
                                issueSaveEntityReferenceCopy(columnEntity);

                                createReferenceRelationship(NESTED_SCHEMA_ATTRIBUTE,
                                                            tableGuid,
                                                            TABLE,
                                                            columnEntity.getGUID(),
                                                            COLUMN);
                            }
                        } else {
                            // debug - found a non table
                        }
                    }
                }
            } catch (TException | TypeErrorException e) {
                // TODO log properly
                System.out.println("Server error: " +e.toString());
                e.printStackTrace();
            }
        }

        private void createConnectionOrientatedEntities( String baseCanonicalName, EntityDetail databaseEntity) throws ConnectorCheckedException {
            String methodName = "createConnectionOrientatedEntities";
            String name = baseCanonicalName + "-connection";
            String canonicalName = baseCanonicalName + "-connection";

            EntityDetail connectionEntity = getEntityDetailSkeleton(methodName,
                                                                    CONNECTION,
                                                                    name,
                                                                    canonicalName,
                                                                    null,
                                                                    false);

            issueSaveEntityReferenceCopy(connectionEntity);

            name = baseCanonicalName + "-" + CONNECTOR_TYPE;
            canonicalName = baseCanonicalName + "-" + CONNECTOR_TYPE;
            EntityDetail connectionTypeEntity = getEntityDetailSkeleton(methodName,
                                                                        CONNECTOR_TYPE,
                                                                        name,
                                                                        canonicalName,
                                                                        null,
                                                                        false);
            issueSaveEntityReferenceCopy(connectionTypeEntity);


            name = baseCanonicalName + "-" + ENDPOINT;
            canonicalName = baseCanonicalName + "-" + ENDPOINT;

            EntityDetail endpointEntity = getEntityDetailSkeleton(methodName,
                                                                  ENDPOINT,
                                                                  name,
                                                                  canonicalName,
                                                                  null,
                                                                  false);
            InstanceProperties instanceProperties = endpointEntity.getProperties();
            repositoryHelper.addStringPropertyToInstance(methodName,
                                                         null,
                                                         "protocol",
                                                         "file",
                                                         methodName);
            // TODO put something sensible in the value - if we can or don't create an Endpoint.
            repositoryHelper.addStringPropertyToInstance(methodName,
                                                         null,
                                                         "networkAddress",
                                                         baseCanonicalName,
                                                         methodName);
            endpointEntity.setProperties(instanceProperties);

            issueSaveEntityReferenceCopy(endpointEntity);
            // create relationships

            // entity guids used to create proxies
            String connectionGuid = connectionEntity.getGUID();
            String dataFileGuid = databaseEntity.getGUID();
            String connectionTypeGuid = connectionTypeEntity.getGUID();
            String endPointGuid = endpointEntity.getGUID();

            // create the 3 relationships
            createReferenceRelationship(CONNECTION_TO_ASSET,
                                        connectionGuid,
                                        CONNECTION,
                                        dataFileGuid,
                                        DATABASE);

            createReferenceRelationship(CONNECTION_CONNECTOR_TYPE,
                                        connectionGuid,
                                        CONNECTION,
                                        connectionTypeGuid,
                                        CONNECTOR_TYPE);

            createReferenceRelationship(CONNECTION_ENDPOINT,
                                        connectionGuid,
                                        CONNECTION,
                                        endPointGuid,
                                        ENDPOINT
                                       );
        }

        private void issueSaveEntityReferenceCopy(EntityDetail entityToAdd) throws ConnectorCheckedException {
            String methodName = "issueSaveEntityReferenceCopy";

            try {
                metadataCollection.saveEntityReferenceCopy(
                        userId,
                        entityToAdd);
            } catch (InvalidParameterException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_PARAMETER_EXCEPTION, methodName, e);
            } catch (RepositoryErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.REPOSITORY_ERROR_EXCEPTION, methodName, e);
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e);
            } catch (PropertyErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.PROPERTY_ERROR_EXCEPTION, methodName, e);
//                } catch (ClassificationErrorException e) {
//                    raiseConnectorCheckedException(FileOMRSErrorCode.CLASSIFICATION_ERROR_EXCEPTION, methodName, e);
//                } catch (StatusNotSupportedException e) {
//                    raiseConnectorCheckedException(FileOMRSErrorCode.STATUS_NOT_SUPPORTED_ERROR_EXCEPTION, methodName, e);
            } catch (HomeEntityException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.HOME_ENTITY_ERROR_EXCEPTION, methodName, e);
            } catch (EntityConflictException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENTITY_CONFLICT_ERROR_EXCEPTION, methodName, e);
            } catch (InvalidEntityException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_ENTITY_ERROR_EXCEPTION, methodName, e);
            } catch (FunctionNotSupportedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.FUNCTION_NOT_SUPPORTED_ERROR_EXCEPTION, methodName, e);
            } catch (UserNotAuthorizedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.USER_NOT_AUTHORIZED_EXCEPTION, methodName, e);
            }
        }

        private void createReferenceRelationship(String relationshipTypeName, String end1GUID, String end1TypeName, String end2GUID, String end2TypeName) throws ConnectorCheckedException {
            String methodName = "createRelationship";


            Relationship relationship = null;
            try {
                relationship = repositoryHelper.getSkeletonRelationship(methodName,
                                                                        metadataCollectionId,
                                                                        InstanceProvenanceType.LOCAL_COHORT,
                                                                        userId,
                                                                        relationshipTypeName);
                // leaving the version as 1 - until we have attributes we need to update
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e);
            }

            String connectionToAssetCanonicalName = end1GUID + "::" + relationshipTypeName + "::" + end2GUID;
            String relationshipGUID = null;
            try {
                relationshipGUID = Base64.getUrlEncoder().encodeToString(connectionToAssetCanonicalName.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENCODING_EXCEPTION, methodName, e, "connectionToAssetCanonicalName", connectionToAssetCanonicalName);
            }

            relationship.setGUID(relationshipGUID);
            //end 1
            EntityProxy entityProxy1 = getEntityProxySkeleton(end1GUID, end1TypeName);
            relationship.setEntityOneProxy(entityProxy1);

            //end 2
            EntityProxy entityProxy2 = getEntityProxySkeleton(end2GUID, end2TypeName);
            relationship.setEntityTwoProxy(entityProxy2);
            try {
                metadataCollection.saveRelationshipReferenceCopy(
                        userId,
                        relationship);
            } catch (InvalidParameterException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_PARAMETER_EXCEPTION, methodName, e);
            } catch (RepositoryErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.REPOSITORY_ERROR_EXCEPTION, methodName, e);
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e);
            } catch (EntityNotKnownException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENTITY_NOT_KNOWN, methodName, e);
            } catch (PropertyErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.PROPERTY_ERROR_EXCEPTION, methodName, e);
            } catch (HomeRelationshipException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.HOME_RELATIONSHIP_ERROR_EXCEPTION, methodName, e);
            } catch (RelationshipConflictException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.RELATIONSHIP_CONFLICT_ERROR_EXCEPTION, methodName, e);
            } catch (InvalidRelationshipException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.INVALID_RELATIONSHIP_ERROR_EXCEPTION, methodName, e);
            } catch (FunctionNotSupportedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.FUNCTION_NOT_SUPPORTED_ERROR_EXCEPTION, methodName, e);
            } catch (UserNotAuthorizedException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.USER_NOT_AUTHORIZED_EXCEPTION, methodName, e);
            }

        }

        private EntityProxy getEntityProxySkeleton(String guid, String typeName) throws ConnectorCheckedException {
            String methodName = "getEntityProxySkeleton";
            EntityProxy proxy = new EntityProxy();
            TypeDefSummary typeDefSummary = repositoryHelper.getTypeDefByName("getEntityProxySkeleton", typeName);
            InstanceType type = null;
            try {
                if (typeDefSummary == null) {
                    throw new TypeErrorException(HMSOMRSErrorCode.TYPEDEF_NAME_NOT_KNOWN.getMessageDefinition(repositoryName, methodName, typeName),
                                                 this.getClass().getName(),
                                                 methodName);
                }
                type = repositoryHelper.getNewInstanceType(methodName, typeDefSummary);
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e);
            }
            proxy.setType(type);
            proxy.setGUID(guid);
            proxy.setMetadataCollectionId(metadataCollectionId);
            proxy.setMetadataCollectionName(metadataCollectionName);
            return proxy;
        }

        private EntityDetail getEntityDetailSkeleton(String originalMethodName,
                                                     String typeName,
                                                     String name,
                                                     String canonicalName,
                                                     Map<String, String> attributeMap,
                                                     boolean generateUniqueVersion

                                                    ) throws ConnectorCheckedException {
            String methodName = "getEntityDetail";

            String guid = null;
            try {
                guid = Base64.getUrlEncoder().encodeToString(canonicalName.getBytes("UTF-8"));
            } catch (UnsupportedEncodingException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.ENCODING_EXCEPTION, methodName, e, "canonicalName", canonicalName);
            }


            InstanceProperties initialProperties = repositoryHelper.addStringPropertyToInstance(methodName,
                                                                                                null,
                                                                                                "name",
                                                                                                name,
                                                                                                methodName);
            initialProperties = repositoryHelper.addStringPropertyToInstance(methodName,
                                                                             initialProperties,
                                                                             "qualifiedName",
                                                                             qualifiedNamePrefix + canonicalName,
                                                                             methodName);
            if (attributeMap != null && !attributeMap.keySet().isEmpty()) {
                addTypeSpecificProperties(initialProperties, attributeMap);
            }

            EntityDetail entityToAdd = new EntityDetail();
            entityToAdd.setProperties(initialProperties);

            // set the provenance as local cohort
            entityToAdd.setInstanceProvenanceType(InstanceProvenanceType.LOCAL_COHORT);
            entityToAdd.setMetadataCollectionId(metadataCollectionId);
//            entityToAdd.setMetadataCollectionName(metadataCollectionName);

            TypeDef typeDef = repositoryHelper.getTypeDefByName(methodName, typeName);

            try {
                if (typeDef == null) {
                    throw new TypeErrorException(HMSOMRSErrorCode.TYPEDEF_NAME_NOT_KNOWN.getMessageDefinition(metadataCollectionName, methodName, typeName),
                                                 this.getClass().getName(),
                                                 originalMethodName);
                }
                InstanceType instanceType = repositoryHelper.getNewInstanceType(repositoryName, typeDef);
                entityToAdd.setType(instanceType);
            } catch (TypeErrorException e) {
                raiseConnectorCheckedException(HMSOMRSErrorCode.TYPE_ERROR_EXCEPTION, methodName, e);
            }

            entityToAdd.setGUID(guid);
            entityToAdd.setStatus(InstanceStatus.ACTIVE);
            // for this sample we only support add and delete so there is only one version
            // if the name changes then this is an add and a delete
            long version = 1;
            if (generateUniqueVersion) {
                version = System.currentTimeMillis();
            }
            entityToAdd.setVersion(version);

            return entityToAdd;

        }

        void addTypeSpecificProperties(InstanceProperties initialProperties, Map<String, String> attributeMap) {
            String methodName = "addTypeSpecificProperties";
            for (String attributeName : attributeMap.keySet()) {
                repositoryHelper.addStringPropertyToInstance(methodName,
                                                             initialProperties,
                                                             attributeName,
                                                             attributeMap.get(attributeName),
                                                             methodName);
            }
        }
    }

    private Classification createTypeEmbeddedClassificationForColumn(String apiName, EntityDetail entity, String dataType) throws TypeErrorException {
        String methodName = "createTypeEmbeddedClassificationForColumn";
        Classification classification = repositoryHelper.getSkeletonClassification(methodName, userId, TYPE_EMBEDDED_ATTRIBUTE, entity.getType().getTypeDefName());

        InstanceProperties instanceProperties = new InstanceProperties();
        repositoryHelper.addStringPropertyToInstance(apiName, instanceProperties, "schemaTypeName", RELATIONAL_COLUMN_TYPE, methodName);
        repositoryHelper.addStringPropertyToInstance(apiName, instanceProperties, "dataType", dataType, methodName);
        classification.setProperties(instanceProperties);
        repositoryHelper.addClassificationToEntity(apiName, entity,classification, methodName);

        return classification;
    }
    private Classification createTypeEmbeddedClassificationForTable(String apiName, EntityDetail entity) throws TypeErrorException {
        String methodName = "createTypeEmbeddedClassificationForTable";
        Classification classification = repositoryHelper.getSkeletonClassification(methodName, userId, TYPE_EMBEDDED_ATTRIBUTE, entity.getType().getTypeDefName());
        InstanceProperties instanceProperties = new InstanceProperties();
        repositoryHelper.addStringPropertyToInstance(apiName, instanceProperties, "schemaTypeName", RELATIONAL_TABLE_TYPE, methodName);
        classification.setProperties(instanceProperties);
        repositoryHelper.addClassificationToEntity(apiName, entity,classification, methodName);

        return classification;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    synchronized public void disconnect() throws ConnectorCheckedException {
        super.disconnect();
        final String methodName = "disconnect";
        pollingThread.stop();
        auditLog.logMessage(methodName, HMSOMRSAuditCode.EVENT_MAPPER_SHUTDOWN.getMessageDefinition(repositoryConnector.getServerName()));
    }

    /**
     * Throws a ConnectorCheckedException based on the provided parameters.
     *
     * @param errorCode  the error code for the exception
     * @param methodName the method name throwing the exception
     * @param cause      the underlying cause of the exception (if any, otherwise null)
     * @param params     any additional parameters for formatting the error message
     * @throws ConnectorCheckedException always
     */
    private void raiseConnectorCheckedException(HMSOMRSErrorCode errorCode, String methodName, Exception cause, String... params) throws ConnectorCheckedException {
        if (cause == null) {
            throw new ConnectorCheckedException(errorCode.getMessageDefinition(params),
                                                this.getClass().getName(),
                                                methodName);
        } else {
            throw new ConnectorCheckedException(errorCode.getMessageDefinition(params),
                                                this.getClass().getName(),
                                                methodName,
                                                cause);
        }
    }

    /**
     * Throws a RepositoryErrorException using the provided parameters.
     *
     * @param errorCode  the error code for the exception
     * @param methodName the name of the method throwing the exception
     * @param cause      the underlying cause of the exception (or null if none)
     * @param params     any parameters for formatting the error message
     * @throws RepositoryErrorException always
     */
    private void raiseRepositoryErrorException(HMSOMRSErrorCode errorCode, String methodName, Throwable cause, String... params) throws RepositoryErrorException {
        if (cause == null) {
            throw new RepositoryErrorException(errorCode.getMessageDefinition(params),
                                               this.getClass().getName(),
                                               methodName);
        } else {
            throw new RepositoryErrorException(errorCode.getMessageDefinition(params),
                                               this.getClass().getName(),
                                               methodName,
                                               cause);
        }
    }


}
