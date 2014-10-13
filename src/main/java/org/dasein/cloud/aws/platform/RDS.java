/**
 * Copyright (C) 2009-2014 Dell, Inc.
 * See annotations for authorship information
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.aws.platform;

import java.util.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.dasein.cloud.CloudException;
import org.dasein.cloud.DayOfWeek;
import org.dasein.cloud.InternalException;
import org.dasein.cloud.ResourceStatus;
import org.dasein.cloud.TimeWindow;
import org.dasein.cloud.aws.AWSCloud;
import org.dasein.cloud.aws.compute.EC2Exception;
import org.dasein.cloud.aws.compute.EC2Method;
import org.dasein.cloud.aws.model.DatabaseProductDefinition;
import org.dasein.cloud.aws.model.DatabaseProvider;
import org.dasein.cloud.aws.model.DatabaseRegion;
import org.dasein.cloud.identity.ServiceAction;
import org.dasein.cloud.platform.*;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.Jiterator;
import org.dasein.util.JiteratorPopulator;
import org.dasein.util.PopulatorThread;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import static org.dasein.cloud.platform.DatabaseLicenseModel.*;

/**
 * AWS RDS Support
 *
 * @author George Reese
 * @author Stas Maksimov
 * @version 2014.08 deprecated methods moved to capabilities
 * @since ?
 */
public class RDS implements RelationalDatabaseSupport {
    static private final Logger logger = AWSCloud.getLogger(RDS.class);
    
    static public final String AUTHORIZE_DB_SECURITY_GROUP_INGRESS = "AuthorizeDBSecurityGroupIngress";
    static public final String CREATE_DB_INSTANCE                  = "CreateDBInstance";
    static public final String CREATE_DB_PARAMETER_GROUP           = "CreateDBParameterGroup";
    static public final String CREATE_DB_SECURITY_GROUP            = "CreateDBSecurityGroup";
    static public final String CREATE_DB_SNAPSHOT                  = "CreateDBSnapshot"; 
    static public final String DELETE_DB_INSTANCE                  = "DeleteDBInstance";
    static public final String DELETE_DB_PARAMETER_GROUP           = "DeleteDBParameterGroup";
    static public final String DELETE_DB_SECURITY_GROUP            = "DeleteDBSecurityGroup";
    static public final String DELETE_DB_SNAPSHOT                  = "DeleteDBSnapshot";
    static public final String DESCRIBE_DB_ENGINE_VERSIONS         = "DescribeDBEngineVersions";
    static public final String DESCRIBE_DB_INSTANCES               = "DescribeDBInstances";
    static public final String DESCRIBE_ENGINE_DEFAULT_PARAMETERS  = "DescribeEngineDefaultParameters";
    static public final String DESCRIBE_DB_PARAMETER_GROUPS        = "DescribeDBParameterGroups";
    static public final String DESCRIBE_DB_PARAMETERS              = "DescribeDBParameters";
    static public final String DESCRIBE_DB_SECURITY_GROUPS         = "DescribeDBSecurityGroups";
    static public final String DESCRIBE_DB_SNAPSHOTS               = "DescribeDBSnapshots";
    static public final String DESCRIBE_DB_EVENTS                  = "DescribeDBEvents";
    static public final String MODIFY_DB_INSTANCE                  = "ModifyDBInstance";
    static public final String MODIFY_DB_PARAMETER_GROUP           = "ModifyDBParameterGroup";
    static public final String REBOOT_DB_INSTANCE                  = "RebootDBInstance";
    static public final String RESET_DB_PARAMETER_GROUP            = "ResetDBParameterGroup";
    static public final String RESTORE_DB_INSTANCE_FROM_SNAPSHOT   = "RestoreDBInstanceFromDBSnapshot";
    static public final String RESTORE_DB_INSTANCE_TO_TIME         = "RestoreDBInstanceToPointInTime";
    static public final String REVOKE_DB_SECURITY_GROUP_INGRESS    = "RevokeDBSecurityGroupIngress";

    static private final String AWS_ENGINE_MYSQL = "MySQL";
    static private final String AWS_ENGINE_POSTGRES = "postgres";
    static private final String AWS_ENGINE_ORACLE_SE1 = "oracle-se1";
    static private final String AWS_ENGINE_ORACLE_SE = "oracle-se";
    static private final String AWS_ENGINE_ORACLE_EE = "oracle-ee";
    static private final String AWS_ENGINE_SQLSERVER_EE = "sqlserver-ee";
    static private final String AWS_ENGINE_SQLSERVER_SE = "sqlserver-se";
    static private final String AWS_ENGINE_SQLSERVER_EX = "sqlserver-ex";
    static private final String AWS_ENGINE_SQLSERVER_WEB = "sqlserver-web";

    static public @Nonnull ServiceAction[] asRDSServiceAction(@Nonnull String action) {
        return null; // TODO: implement me
    }

    private volatile transient RDSCapabilities capabilities;

    private AWSCloud provider;
    
    RDS(AWSCloud provider) {
        this.provider = provider;
    }
    
    public void addAccess(String providerDatabaseId, String sourceCidr) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.addAccess");
        try {
            Iterator<String> securityGroups = getSecurityGroups(providerDatabaseId).iterator();
            String id;

            if( !securityGroups.hasNext() ) {
                id = createSecurityGroup(providerDatabaseId);
                setSecurityGroup(providerDatabaseId, id);
            }
            else {
                id = securityGroups.next();
            }

            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), AUTHORIZE_DB_SECURITY_GROUP_INGRESS);
            EC2Method method;

            parameters.put("DBSecurityGroupName", id);
            parameters.put("CIDRIP", sourceCidr);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.equals("AuthorizationAlreadyExists") ) {
                    return;
                }
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private String getWindowString(TimeWindow window) {
        StringBuilder str = new StringBuilder();
    
        if( window.getStartDayOfWeek() == null ) {
            str.append("*");
        }
        else {
            str.append(window.getStartDayOfWeek().getShortString());
        }
        str.append(":");
        str.append(String.valueOf(window.getStartHour()));
        str.append(":");
        str.append(String.valueOf(window.getStartMinute()));
        str.append("-");
        if( window.getEndDayOfWeek() == null ) {
            str.append("*");
        }
        else {
            str.append(window.getEndDayOfWeek().getShortString());
        }
        str.append(":");
        str.append(String.valueOf(window.getEndHour()));
        str.append(":");
        str.append(String.valueOf(window.getEndMinute()));        
        return str.toString();
    }
    
    @Override
    public void alterDatabase(String providerDatabaseId, boolean applyImmediately, String productSize, int storageInGigabytes, String configurationId, String newAdminUser, String newAdminPassword, int newPort, int snapshotRetentionInDays, TimeWindow preferredMaintenanceWindow, TimeWindow preferredBackupWindow) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.alterDatabase");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), MODIFY_DB_INSTANCE);
            EC2Method method;

            parameters.put("DBInstanceIdentifier", providerDatabaseId);
            parameters.put("ApplyImmediately", String.valueOf(applyImmediately));
            if( configurationId != null ) {
                parameters.put("DBParameterGroupName", configurationId);
            }
            if( preferredMaintenanceWindow != null ) {
                String window = getWindowString(preferredMaintenanceWindow);

                parameters.put("PreferredMaintenanceWindow", window);
            }
            if( preferredBackupWindow != null ) {
                String window = getWindowString(preferredBackupWindow);

                parameters.put("PreferredBackupWindow", window);
            }
            if( newAdminPassword != null ) {
                parameters.put("MasterUserPassword", newAdminPassword);
            }
            if( storageInGigabytes > 0 ) {
                parameters.put("AllocatedStorage", String.valueOf(storageInGigabytes));
            }
            if( productSize != null ) {
                parameters.put("DBInstanceClass", productSize);
            }
            if( snapshotRetentionInDays > -1 ) {
                parameters.put("BackupRetentionPeriod", String.valueOf(snapshotRetentionInDays));
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private String createSecurityGroup(String id) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.createSecurityGroup");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), CREATE_DB_SECURITY_GROUP);
            EC2Method method;

            parameters.put("DBSecurityGroupName", id);
            parameters.put("DBSecurityGroupDescription", "Auto-generated DB security group for " + id);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                String code = e.getCode();

                if( code != null && code.equals("DBSecurityGroupAlreadyExists") ) {
                    return id;
                }
                throw new CloudException(e);
            }
            return id;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public String createFromScratch(String databaseName, DatabaseProduct product, String engineVersion, String withAdminUser, String withAdminPassword, int hostPort) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.createFromScratch");
        try {
            Map<String,String> parameters;
            String id = toIdentifier(databaseName);
            EC2Method method;
            NodeList blocks;
            Document doc;

            if( engineVersion == null ) {
                engineVersion = getDefaultVersion(product.getEngine());
            }
            int size = product.getStorageInGigabytes();

            if( size < 5 ) {
                size = 5;
            }
            
            parameters = provider.getStandardRdsParameters(provider.getContext(), CREATE_DB_INSTANCE);
            parameters.put("DBInstanceIdentifier", id);
            parameters.put("AllocatedStorage", String.valueOf(size));
            parameters.put("DBInstanceClass", product.getProductSize());
            parameters.put("Engine", getEngineString(product.getEngine()));
            parameters.put("EngineVersion", engineVersion);
            parameters.put("MasterUsername", withAdminUser);
            parameters.put("MasterUserPassword", withAdminPassword);
            parameters.put("Port", String.valueOf(hostPort));

            String ec2Type = provider.getDataCenterServices().isRegionEC2VPC(provider.getContext().getRegionId());
            if(ec2Type.equals(AWSCloud.PLATFORM_EC2)){
                String securityGroupId = createSecurityGroup(id);
                parameters.put("DBSecurityGroups.member.1", securityGroupId);
            }

            // TODO: refactor into a toLicense() method
            String license = "general-public-license";
            switch (product.getLicenseModel()) {
                case BRING_YOUR_OWN_LICENSE:
                    license = "bring-your-own-license";
                    break;
                case LICENSE_INCLUDED:
                    license = "license-included";
                    break;
                case POSTGRESQL_LICENSE:
                    license = "postgresql-license";
                    break;
            }
            parameters.put("LicenseModel", license);

            if( product.isHighAvailability() ) {
                parameters.put("MultiAZ", "true");
            }
            else if( product.getProviderDataCenterId() != null ) {
                // set az if not empty, otherwise the region's default is used
                parameters.put("AvailabilityZone", product.getProviderDataCenterId());
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBInstanceIdentifier");
            if( blocks.getLength() > 0 ) {
                return blocks.item(0).getFirstChild().getNodeValue().trim();
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }
    
    public String createFromLatest(String databaseName, String providerDatabaseId, String productSize, String providerDataCenterId, int hostPort) throws InternalException, CloudException {
        APITrace.begin(provider, "RDBMS.createFromLatest");
        try {
            Map<String,String> parameters;
            String id = toIdentifier(databaseName);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters = provider.getStandardRdsParameters(provider.getContext(), RESTORE_DB_INSTANCE_TO_TIME);
            parameters.put("SourceDBInstanceIdentifier", providerDatabaseId);
            parameters.put("UseLatestRestorableTime", "True");
            parameters.put("TargetDBInstanceIdentifier", id);
            parameters.put("DBInstanceClass", productSize);
            parameters.put("Port", String.valueOf(hostPort));
            if( providerDataCenterId == null ) {
                parameters.put("MultiAZ", "true");
            }
            else {
                parameters.put("AvailabilityZone", providerDataCenterId);
                parameters.put("MultiAZ", "false");
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBInstanceIdentifier");
            if( blocks.getLength() > 0 ) {
                return id;
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    public String createFromSnapshot(String databaseName, String providerDatabaseId, String providerDbSnapshotId, String productSize, String providerDataCenterId, int hostPort) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.createFromSnapshot");
        try {
            Map<String,String> parameters;
            String id = toIdentifier(databaseName);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters = provider.getStandardRdsParameters(provider.getContext(), RESTORE_DB_INSTANCE_FROM_SNAPSHOT);
            parameters.put("DBSnapshotIdentifier", providerDbSnapshotId);
            parameters.put("DBInstanceIdentifier", id);
            parameters.put("DBInstanceClass", productSize);
            parameters.put("Port", String.valueOf(hostPort));
            if( providerDataCenterId == null ) {
                parameters.put("MultiAZ", "true");
            }
            else {
                parameters.put("AvailabilityZone", providerDataCenterId);
                parameters.put("MultiAZ", "false");
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBInstanceIdentifier");
            if( blocks.getLength() > 0 ) {
                return id;
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    public String createFromTimestamp(String databaseName, String providerDatabaseId, long beforeTimestamp, String productSize, String providerDataCenterId, int hostPort) throws InternalException, CloudException {
        APITrace.begin(provider, "RDBMS.createFromTimestamp");
        try {
            Map<String,String> parameters;
            String id = toIdentifier(databaseName);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters = provider.getStandardRdsParameters(provider.getContext(), RESTORE_DB_INSTANCE_TO_TIME);
            parameters.put("SourceDBInstanceIdentifier", providerDatabaseId);
            parameters.put("RestoreTime", provider.getTimestamp(beforeTimestamp, false));
            parameters.put("TargetDBInstanceIdentifier", id);
            parameters.put("DBInstanceClass", productSize);
            parameters.put("Port", String.valueOf(hostPort));
            if( providerDataCenterId == null ) {
                parameters.put("MultiAZ", "true");
            }
            else {
                parameters.put("AvailabilityZone", providerDataCenterId);
                parameters.put("MultiAZ", "false");
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBInstanceIdentifier");
            if( blocks.getLength() > 0 ) {
                return id;
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull RelationalDatabaseCapabilities getCapabilities() throws InternalException, CloudException {
        if( capabilities == null ) {
            capabilities = new RDSCapabilities(provider);
        }
        return capabilities;
    }

    public DatabaseConfiguration getConfiguration(String providerConfigurationId) throws CloudException, InternalException {
        if( providerConfigurationId == null ) {
            return null;
        }
        PopulatorThread<DatabaseConfiguration> populator;
        final String id = providerConfigurationId;

        provider.hold();
        populator = new PopulatorThread<DatabaseConfiguration>(new JiteratorPopulator<DatabaseConfiguration>() {
            public void populate(Jiterator<DatabaseConfiguration> iterator) throws CloudException, InternalException {
                try {
                    populateConfigurationList(id, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        Iterator<DatabaseConfiguration> it = populator.getResult().iterator();
        
        if( it.hasNext() ) {
            return it.next();
        }
        return null;
    }

    public Database getDatabase(String providerDatabaseId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.getDatabase");
        try {
            if( providerDatabaseId == null ) {
                return null;
            }
            for( Database database : listDatabases(providerDatabaseId) ) {
                if( database.getProviderDatabaseId().equals(providerDatabaseId) ) {
                    return database;
                }
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    static private volatile List<DatabaseEngine> engines = null;
    
    @Override
    public Iterable<DatabaseEngine> getDatabaseEngines() {
        if( engines == null ) {
            engines = Arrays.asList(
                    DatabaseEngine.MYSQL,
                    DatabaseEngine.ORACLE_EE,
                    DatabaseEngine.ORACLE_SE,
                    DatabaseEngine.ORACLE_SE1,
                    DatabaseEngine.POSTGRES,
                    DatabaseEngine.SQLSERVER_EE,
                    DatabaseEngine.SQLSERVER_EX,
                    DatabaseEngine.SQLSERVER_SE,
                    DatabaseEngine.SQLSERVER_WEB
            );
        }
        return engines;
    }
    
    static private volatile Map<DatabaseEngine,String> defaultVersions = new HashMap<DatabaseEngine,String>();
    static private volatile Map<DatabaseEngine,Collection<String>> engineVersions = new HashMap<DatabaseEngine,Collection<String>>();
    
    @Override
    public String getDefaultVersion(DatabaseEngine forEngine) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.getDefaultVersion");
        try {
            String version = defaultVersions.get(forEngine);

            if( version == null ) {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_ENGINE_VERSIONS);
                EC2Method method;
                Document doc;

                parameters.put("Engine", getEngineString(forEngine));
                parameters.put("DefaultOnly", "true");
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    throw new CloudException(e);
                };
                NodeList blocks = doc.getElementsByTagName("DBEngineVersions");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("DBEngineVersion") ) {
                            NodeList attrs = item.getChildNodes();

                            for( int k=0; k<attrs.getLength(); k++ ) {
                                Node attr = attrs.item(k);

                                if( attr.getNodeName().equals("EngineVersion") ) {
                                    version = attr.getFirstChild().getNodeValue().trim();
                                    defaultVersions.put(forEngine, version);
                                    return version;
                                }
                            }
                        }
                    }
                }
            }
            if( version == null ) {
                for( String v : getSupportedVersions(forEngine) ) {
                    return v;
                }
            }
            return version;
        }
        finally {
            APITrace.end();
        }
    }
    
    @Override
    public Iterable<String> getSupportedVersions(DatabaseEngine forEngine) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.getSupportedVersions");
        try {
            Collection<String> versions = engineVersions.get(forEngine);

            if( versions == null ) {
                ArrayList<String> list = new ArrayList<String>();
                String marker = null;

                do {
                    Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_ENGINE_VERSIONS);
                    EC2Method method;
                    Document doc;

                    parameters.put("Engine", getEngineString(forEngine));
                    method = new EC2Method(provider, getRDSUrl(), parameters);
                    try {
                        doc = method.invoke();
                    }
                    catch( EC2Exception e ) {
                        throw new CloudException(e);
                    }
                    marker = null;
                    NodeList blocks;
                    blocks = doc.getElementsByTagName("Marker");
                    if( blocks.getLength() > 0 ) {
                        for( int i=0; i<blocks.getLength(); i++ ) {
                            Node item = blocks.item(i);

                            if( item.hasChildNodes() ) {
                                marker = item.getFirstChild().getNodeValue().trim();
                            }
                        }
                        if( marker != null ) {
                            break;
                        }
                    }
                    blocks = doc.getElementsByTagName("DBEngineVersions");
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        NodeList items = blocks.item(i).getChildNodes();

                        for( int j=0; j<items.getLength(); j++ ) {
                            Node item = items.item(j);

                            if( item.getNodeName().equals("DBEngineVersion") ) {
                                NodeList attrs = item.getChildNodes();

                                for( int k=0; k<attrs.getLength(); k++ ) {
                                    Node attr = attrs.item(k);

                                    if( attr.getNodeName().equals("EngineVersion") ) {
                                        list.add(attr.getFirstChild().getNodeValue().trim());
                                    }
                                }
                            }
                        }
                    }
                } while( marker != null );
                if( list.isEmpty() ) {
                    return Collections.emptyList();
                }
                versions = list;
                engineVersions.put(forEngine, versions);
            }
            return versions;
        }
        finally {
            APITrace.end();
        }
    }
    
    private volatile List<DatabaseProduct> databaseProducts = null;

    @Override
    public Iterable<DatabaseProduct> listDatabaseProducts( DatabaseEngine engine ) throws CloudException, InternalException {
        List<DatabaseProduct> products = databaseProducts;

        if( products == null ) {
            products = new ArrayList<DatabaseProduct>();
            DatabaseProvider databaseProvider = DatabaseProvider.fromFile("/org/dasein/cloud/aws/dbproducts.json", "AWS");

            org.dasein.cloud.aws.model.DatabaseEngine databaseEngine = databaseProvider.findEngine(getEngineString(engine));

            if( databaseEngine != null ) {
                for ( DatabaseRegion region : databaseEngine.getRegions() ) {
                    if( region.getName().equalsIgnoreCase( provider.getContext().getRegionId()) ) {
                        for( org.dasein.cloud.aws.model.DatabaseProduct databaseProduct : region.getProducts() ) {
                            DatabaseProduct product = new DatabaseProduct(databaseProduct.getName());
                            product.setEngine(engine);
                            product.setHighAvailability(databaseProduct.isHighAvailability());
                            product.setStandardHourlyRate(databaseProduct.getHourlyRate());
                            product.setStandardIoRate(databaseProduct.getIoRate());
                            product.setStandardStorageRate(databaseProduct.getStorageRate());
                            DatabaseLicenseModel lic = GENERAL_PUBLIC_LICENSE;
                            if( "included".equalsIgnoreCase(databaseProduct.getLicense())) {
                                lic = LICENSE_INCLUDED;
                            } else if( "byol".equalsIgnoreCase(databaseProduct.getLicense())) {
                                lic = BRING_YOUR_OWN_LICENSE;
                            } else if( "postgres".equalsIgnoreCase(databaseProduct.getLicense())) {
                                lic = POSTGRESQL_LICENSE;
                            }
                            product.setLicenseModel(lic);
                            product.setCurrency(databaseProduct.getCurrency());
                            DatabaseProductDefinition def = databaseProvider.findProductDefinition(databaseProduct.getName());
                            if( def != null) {
                                product.setName(String.format("%.2fGB RAM, %d CPU, %s Network Performance", def.getMemory(), def.getvCpus(), def.getNetworkPerformance()));
                            }
                            product.setStorageInGigabytes(databaseProduct.getMinStorage());
                            products.add(product);
                        }
                    }
                }
            }
        }
        return databaseProducts = products;
    }


    @Override
    @Deprecated
    public Iterable<DatabaseProduct> getDatabaseProducts(DatabaseEngine engine) throws CloudException, InternalException {
        return listDatabaseProducts(engine);
    }

    private DayOfWeek getDay(String str) {
        if( str.equalsIgnoreCase("Sun") ) {
            return DayOfWeek.SUNDAY;
        }
        else if( str.equalsIgnoreCase("Mon") ) {
            return DayOfWeek.MONDAY;
        }
        else if( str.equalsIgnoreCase("Tue") ) {
            return DayOfWeek.TUESDAY;
        }
        else if( str.equalsIgnoreCase("Wed") ) {
            return DayOfWeek.WEDNESDAY;
        }
        else if( str.equalsIgnoreCase("Thu") ) {
            return DayOfWeek.THURSDAY;
        }
        else if( str.equalsIgnoreCase("Fri") ) {
            return DayOfWeek.FRIDAY;
        }
        else if( str.equalsIgnoreCase("Sat") ) {
            return DayOfWeek.SATURDAY;
        }
        return null;
    }
    
    private TimeWindow getTimeWindow(String start, String end) {
        String[] parts = start.split(":");
        int startHour = 0, endHour = 0;
        int startMinute = 0, endMinute = 0;
        DayOfWeek startDay = null, endDay = null;
        
        if( parts.length < 3 ) {
            if( parts.length < 2 ) {
                try { 
                    startHour = Integer.parseInt(start);
                }
                catch( NumberFormatException ignore ) {
                    // ignore
                }
            }
            else {
                try {
                    startHour = Integer.parseInt(parts[0]);
                }
                catch( NumberFormatException ignore ) {
                    // ignore
                }
                try {
                    startMinute = Integer.parseInt(parts[1]);
                }
                catch( NumberFormatException e ) {
                    // ignore
                }
            }
        }
        else {
            startDay = getDay(parts[0]);
            try {
                startHour = Integer.parseInt(parts[1]);
            }
            catch( NumberFormatException ignore ) {
                // ignore
            }
            try {
                startMinute = Integer.parseInt(parts[2]);
            }
            catch( NumberFormatException e ) {
                // ignore
            }
        }
        parts = end.split(":");
        if( parts.length < 3 ) {
            if( parts.length < 2 ) {
                try { 
                    endHour = Integer.parseInt(start);
                }
                catch( NumberFormatException ignore ) {
                    // ignore
                }
            }
            else {
                try {
                    endHour = Integer.parseInt(parts[0]);
                }
                catch( NumberFormatException ignore ) {
                    // ignore
                }
                try {
                    endMinute = Integer.parseInt(parts[1]);
                }
                catch( NumberFormatException e ) {
                    // ignore
                }
            }
        }
        else {
            endDay = getDay(parts[0]);
            try {
                endHour = Integer.parseInt(parts[1]);
            }
            catch( NumberFormatException ignore ) {
                // ignore
            }
            try {
                endMinute = Integer.parseInt(parts[2]);
            }
            catch( NumberFormatException e ) {
                // ignore
            }
        }
        TimeWindow window = new TimeWindow();
        
        window.setStartDayOfWeek(startDay);
        window.setStartHour(startHour);
        window.setStartMinute(startMinute);
        window.setEndDayOfWeek(endDay);
        window.setEndHour(endHour);
        window.setEndMinute(endMinute);
        return window;
    }

    /**
     * Get Amazon-specific engine name
     * @param engine database engine
     * @return Amazon-spefic engine name, returns 'MySQL' if engine was null.
     */
    private String getEngineString(@Nullable DatabaseEngine engine) {
        if( engine == null ) {
            return AWS_ENGINE_MYSQL;
        }
        switch( engine ) {
            case ORACLE_SE1: return AWS_ENGINE_ORACLE_SE1;
            case ORACLE_SE: return AWS_ENGINE_ORACLE_SE;
            case ORACLE_EE: return AWS_ENGINE_ORACLE_EE;
            case SQLSERVER_SE: return AWS_ENGINE_SQLSERVER_SE;
            case SQLSERVER_EE: return AWS_ENGINE_SQLSERVER_EE;
            case SQLSERVER_EX: return AWS_ENGINE_SQLSERVER_EX;
            case SQLSERVER_WEB: return AWS_ENGINE_SQLSERVER_WEB;
            case POSTGRES: return AWS_ENGINE_POSTGRES;
            case MYSQL:
            default: return AWS_ENGINE_MYSQL;
        }
    }

    @Deprecated
    public String getProviderTermForDatabase(Locale locale) {
        try {
            return getCapabilities().getProviderTermForDatabase(locale);
        } catch( InternalException e ) {
        } catch( CloudException e ) {
        }
        return "database"; // legacy
    }
    
    @Deprecated
    public String getProviderTermForSnapshot(Locale locale) {
        try {
            return getCapabilities().getProviderTermForSnapshot(locale);
        } catch( InternalException e ) {
        } catch( CloudException e ) {
        }
        return "snapshot"; // legacy
    }
    
    
    private String getRDSUrl() throws InternalException, CloudException {
        //noinspection ConstantConditions
        return ("https://rds." + provider.getContext().getRegionId() + ".amazonaws.com");
    }
    
    private Iterable<String> getSecurityGroups(String databaseId) throws CloudException, InternalException {
        PopulatorThread<String> populator;
        final String dbId = databaseId;

        provider.hold();
        populator = new PopulatorThread<String>(new JiteratorPopulator<String>() {
            public void populate(Jiterator<String> iterator) throws CloudException, InternalException {
                try {
                    populateSecurityGroupIds(dbId, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        return populator.getResult();
    }
    
    public DatabaseSnapshot getSnapshot(String providerDbSnapshotId) throws CloudException, InternalException {
        if( providerDbSnapshotId == null ) {
            return null;
        }
        PopulatorThread<DatabaseSnapshot> populator;
        final String id = providerDbSnapshotId;

        provider.hold();
        populator = new PopulatorThread<DatabaseSnapshot>(new JiteratorPopulator<DatabaseSnapshot>() {
            public void populate(Jiterator<DatabaseSnapshot> iterator) throws CloudException, InternalException {
                try {
                    populateSnapshotList(id, null, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        Iterator<DatabaseSnapshot> it = populator.getResult().iterator();
        
        if( it.hasNext() ) {
            return it.next();
        }
        return null;
    }
    
    public boolean isSubscribed() throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.isSubscribed");
        try {
            Cache<Map> cache = Cache.getInstance(provider, "RDBMS.isSubscribed", Map.class, CacheLevel.REGION_ACCOUNT);
            Collection<Map> subscribed = (Collection<Map>)cache.get(provider.getContext());
            if (subscribed != null) {
                return ((Boolean)subscribed.iterator().next().get(AWSCloud.TRUTHMAP_KEY)).booleanValue();
            }

            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_INSTANCES);
            EC2Method method = new EC2Method(provider, getRDSUrl(), parameters);

            try {
                method.invoke();
                cache.put(provider.getContext(), Collections.singleton(AWSCloud.TRUTHMAP_TRUE));
                return true;
            }
            catch( EC2Exception e ) {
                if( e.getStatus() == HttpServletResponse.SC_UNAUTHORIZED || e.getStatus() == HttpServletResponse.SC_FORBIDDEN ) {
                    cache.put(provider.getContext(), Collections.singleton(AWSCloud.TRUTHMAP_FALSE));
                    return false;
                }
                String code = e.getCode();

                if( code != null && (code.equals("SubscriptionCheckFailed") || code.equals("AuthFailure") || code.equals("SignatureDoesNotMatch") || code.equals("InvalidClientTokenId") || code.equals("OptInRequired")) ) {
                    cache.put(provider.getContext(), Collections.singleton(AWSCloud.TRUTHMAP_FALSE));
                    return false;
                }
                logger.warn(e.getSummary());
                if( logger.isDebugEnabled() ) {
                    e.printStackTrace();
                }
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }

    @Deprecated
    public boolean isSupportsFirewallRules() {
        try {
            return capabilities.isSupportsFirewallRules();
        } catch( CloudException e ) {
        } catch( InternalException e ) {
        }
        return true;
    }

    @Deprecated
    public boolean isSupportsHighAvailability() throws CloudException, InternalException {
        return getCapabilities().isSupportsHighAvailability();
    }

    @Deprecated
    public boolean isSupportsLowAvailability() throws CloudException, InternalException {
        return getCapabilities().isSupportsLowAvailability();
    }
    
    @Deprecated
    public boolean isSupportsMaintenanceWindows() {
        try {
            return getCapabilities().isSupportsMaintenanceWindows();
        } catch( CloudException e ) {
        } catch( InternalException e ) {
        }
        return true; // legacy
    }

    @Deprecated
    public boolean isSupportsSnapshots() {
        try {
            return getCapabilities().isSupportsSnapshots();
        } catch( CloudException e ) {
        } catch( InternalException e ) {
        }
        return true; // legacy
    }
    
    public Iterable<String> listAccess(String toProviderDatabaseId) throws CloudException, InternalException {
        PopulatorThread<String> idPopulator, accessPopulator;
        final String dbId = toProviderDatabaseId;

        provider.hold();
        idPopulator = new PopulatorThread<String>(new JiteratorPopulator<String>() {
            public void populate(Jiterator<String> iterator) throws CloudException, InternalException {
                try {
                    populateSecurityGroupIds(dbId, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        idPopulator.populate();
        
        final Iterable<String> ids = idPopulator.getResult();
        provider.hold();
        accessPopulator = new PopulatorThread<String>(new JiteratorPopulator<String>() {
            public void populate(Jiterator<String> iterator) throws CloudException, InternalException {
                try {
                    for( String id : ids ) {
                        populateAccess(id, iterator);
                    }
                }
                finally {
                    provider.release();
                }
            }
        });
        accessPopulator.populate();
        return accessPopulator.getResult();
    }
    
    public Iterable<DatabaseConfiguration> listConfigurations() throws CloudException, InternalException {
        PopulatorThread<DatabaseConfiguration> populator;
        
        populator = new PopulatorThread<DatabaseConfiguration>(new JiteratorPopulator<DatabaseConfiguration>() {
            public void populate(Jiterator<DatabaseConfiguration> iterator) throws CloudException, InternalException {
                populateConfigurationList(null, iterator);
            }
        });
        populator.populate();
        return populator.getResult(); 
    }

    @Override
    public @Nonnull Iterable<ResourceStatus> listDatabaseStatus() throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.listDatabaseStatus");
        try {
            ArrayList<ResourceStatus> list = new ArrayList<ResourceStatus>();
            String marker = null;

            do {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_INSTANCES);
                EC2Method method;
                NodeList blocks;
                Document doc;

                if( marker != null ) {
                    parameters.put("Marker", marker);
                }
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    throw new CloudException(e);
                }
                marker = null;
                blocks = doc.getElementsByTagName("Marker");
                if( blocks.getLength() > 0 ) {
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        Node item = blocks.item(i);

                        if( item.hasChildNodes() ) {
                            marker = item.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( marker != null ) {
                        break;
                    }
                }
                blocks = doc.getElementsByTagName("DBInstances");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("DBInstance") ) {
                            ResourceStatus status = toDatabaseStatus(item);

                            if( status != null ) {
                                list.add(status);
                            }
                        }
                    }
                }
            } while( marker != null );
            return list;
        }
        finally {
            APITrace.end();
        }
    }

    public Iterable<Database> listDatabases() throws CloudException, InternalException {
        return listDatabases(null);
    }
    
    private Iterable<Database> listDatabases(String targetId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.listDatabases");
        try {
            ArrayList<Database> list = new ArrayList<Database>();
            String marker = null;

            do {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_INSTANCES);
                EC2Method method;
                NodeList blocks;
                Document doc;

                if( marker != null ) {
                    parameters.put("Marker", marker);
                }
                if( targetId != null ) {
                    parameters.put("DBInstanceIdentifier", targetId);
                }
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    if( targetId != null ) {
                        String code = e.getCode();

                        if( code != null && code.equals("DBInstanceNotFound") || code.equals("InvalidParameterValue") ) {
                            return list;
                        }
                    }
                    throw new CloudException(e);
                }
                marker = null;
                blocks = doc.getElementsByTagName("Marker");
                if( blocks.getLength() > 0 ) {
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        Node item = blocks.item(i);

                        if( item.hasChildNodes() ) {
                            marker = item.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( marker != null ) {
                        break;
                    }
                }
                blocks = doc.getElementsByTagName("DBInstances");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("DBInstance") ) {
                            Database db = toDatabase(item);

                            if( db != null ) {
                                list.add(db);
                            }
                        }
                    }
                }
            } while( marker != null );
            return list;
        }
        finally {
            APITrace.end();
        }
    }
    
    public Collection<ConfigurationParameter> listParameters(String forProviderConfigurationId) throws CloudException, InternalException {
        PopulatorThread<ConfigurationParameter> populator;
        final String id = forProviderConfigurationId;
        
        provider.hold();
        populator = new PopulatorThread<ConfigurationParameter>(new JiteratorPopulator<ConfigurationParameter>() {
            public void populate(Jiterator<ConfigurationParameter> iterator) throws CloudException, InternalException {
                try {
                    populateParameterList(id, null, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        return populator.getResult();
    }
    
    public Collection<ConfigurationParameter> listDefaultParameters(DatabaseEngine engine) throws CloudException, InternalException {
        PopulatorThread<ConfigurationParameter> populator;
        final DatabaseEngine dbEngine = engine;
        
        provider.hold();
        populator = new PopulatorThread<ConfigurationParameter>(new JiteratorPopulator<ConfigurationParameter>() {
            public void populate(Jiterator<ConfigurationParameter> iterator) throws CloudException, InternalException {
                try {
                    populateParameterList(null, dbEngine, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        return populator.getResult();
    }
    
    public Iterable<DatabaseSnapshot> listSnapshots(String forOptionalProviderDatabaseId) throws CloudException, InternalException {
        PopulatorThread<DatabaseSnapshot> populator;
        
        provider.hold();
        final String id = (forOptionalProviderDatabaseId == null ? null : forOptionalProviderDatabaseId);
        populator = new PopulatorThread<DatabaseSnapshot>(new JiteratorPopulator<DatabaseSnapshot>() {
            public void populate(Jiterator<DatabaseSnapshot> iterator) throws CloudException, InternalException {
                try {
                    populateSnapshotList(null, id, iterator);
                }
                finally {
                    provider.release();
                }
            }
        });
        populator.populate();
        return populator.getResult();
    }

    @Override
    public @Nonnull String[] mapServiceAction(@Nonnull ServiceAction action) {
        return new String[0];     // TODO: implement me
    }

    private void populateAccess(String securityGroupId, Jiterator<String> iterator) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.populateDBSGAccess");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_SECURITY_GROUPS);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("DBSecurityGroupName", securityGroupId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBSecurityGroups");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("DBSecurityGroup") ) {
                        NodeList attrs = item.getChildNodes();

                        for( int k=0; k<attrs.getLength(); k++ ) {
                            Node attr = attrs.item(k);
                            String name;

                            name = attr.getNodeName();
                            if( name.equalsIgnoreCase("IPRanges") ) {
                                if( attr.hasChildNodes() ) {
                                    NodeList ranges = attr.getChildNodes();

                                    for( int l=0; l<ranges.getLength(); l++ ) {
                                        Node range = ranges.item(l);

                                        if( range.hasChildNodes() ) {
                                            NodeList rangeAttrs = range.getChildNodes();
                                            String cidr = null;
                                            boolean authorized = false;

                                            for( int m=0; m<rangeAttrs.getLength(); m++ ) {
                                                Node ra = rangeAttrs.item(m);

                                                if( ra.getNodeName().equalsIgnoreCase("Status") ) {
                                                    authorized = ra.getFirstChild().getNodeValue().trim().equalsIgnoreCase("authorized");
                                                }
                                                else if( ra.getNodeName().equalsIgnoreCase("CIDRIP") ) {
                                                    cidr = ra.getFirstChild().getNodeValue().trim();
                                                }
                                            }
                                            if( cidr != null && authorized ) {
                                                iterator.push(cidr);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private void populateConfigurationList(String targetId, Jiterator<DatabaseConfiguration> iterator) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.populateConfigurationList");
        try {
            String marker = null;

            do {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_PARAMETER_GROUPS);
                EC2Method method;
                NodeList blocks;
                Document doc;

                if( marker != null ) {
                    parameters.put("Marker", marker);
                }
                if( targetId != null ) {
                    parameters.put("DBParameterGroupName", targetId);
                }
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    throw new CloudException(e);
                }
                marker = null;
                blocks = doc.getElementsByTagName("Marker");
                if( blocks.getLength() > 0 ) {
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        Node item = blocks.item(i);

                        if( item.hasChildNodes() ) {
                            marker = item.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( marker != null ) {
                        break;
                    }
                }
                blocks = doc.getElementsByTagName("DBParameterGroups");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("DBParameterGroup") ) {
                            DatabaseConfiguration cfg = toConfiguration(item);

                            if( cfg != null ) {
                                iterator.push(cfg);
                            }
                        }
                    }
                }
            } while( marker != null );
        }
        finally {
            APITrace.end();
        }
    }
    
    private void populateParameterList(String cfgId, DatabaseEngine engine, Jiterator<ConfigurationParameter> iterator) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.populateParameterList");
        try {
            String marker = null;

            do {
                Map<String,String> parameters;
                EC2Method method;
                NodeList blocks;
                Document doc;

                if( cfgId != null ) {
                    parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_PARAMETERS);
                    parameters.put("DBParameterGroupName", cfgId);
                }
                else {
                    parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_ENGINE_DEFAULT_PARAMETERS);
                    parameters.put("Engine", getEngineString(engine));
                }
                if( marker != null ) {
                    parameters.put("Marker", marker);
                }
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    throw new CloudException(e);
                }
                marker = null;
                blocks = doc.getElementsByTagName("Marker");
                if( blocks.getLength() > 0 ) {
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        Node item = blocks.item(i);

                        if( item.hasChildNodes() ) {
                            marker = item.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( marker != null ) {
                        break;
                    }
                }
                blocks = doc.getElementsByTagName("Parameters");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("Parameter") ) {
                            ConfigurationParameter param = toParameter(item);

                            if( param != null ) {
                                iterator.push(param);
                            }
                        }
                    }
                }
            } while( marker != null );
        }
        finally {
            APITrace.end();
        }
    }
    
    private void populateSecurityGroupIds(String providerDatabaseId, Jiterator<String> iterator) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.populateDBSecurityGroups");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_INSTANCES);
            EC2Method method;
            NodeList blocks;
            Document doc;

            parameters.put("DBInstanceIdentifier", providerDatabaseId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                doc = method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            blocks = doc.getElementsByTagName("DBInstances");
            for( int i=0; i<blocks.getLength(); i++ ) {
                NodeList items = blocks.item(i).getChildNodes();

                for( int j=0; j<items.getLength(); j++ ) {
                    Node item = items.item(j);

                    if( item.getNodeName().equals("DBInstance") ) {
                        NodeList attrs = item.getChildNodes();

                        for( int k=0; k<attrs.getLength(); k++ ) {
                            Node attr = attrs.item(k);
                            String name;

                            name = attr.getNodeName();
                            if( name.equalsIgnoreCase("DBSecurityGroups") ) {
                                if( attr.hasChildNodes() ) {
                                    NodeList groups = attr.getChildNodes();

                                    for( int l=0; l<groups.getLength(); l++ ) {
                                        Node group = groups.item(l);

                                        if( group.hasChildNodes() ) {
                                            NodeList groupAttrs = group.getChildNodes();
                                            String groupName = null;
                                            boolean active = false;

                                            for( int m=0; m<groupAttrs.getLength(); m++ ) {
                                                Node ga = groupAttrs.item(m);

                                                if( ga.getNodeName().equalsIgnoreCase("Status") ) {
                                                    active = ga.getFirstChild().getNodeValue().trim().equalsIgnoreCase("active");
                                                }
                                                else if( ga.getNodeName().equalsIgnoreCase("DBSecurityGroupName") ) {
                                                    groupName = ga.getFirstChild().getNodeValue().trim();
                                                }
                                            }
                                            if( groupName != null && active ) {
                                                iterator.push(groupName);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private void populateSnapshotList(String snapshotId, String databaseId, Jiterator<DatabaseSnapshot> iterator) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.populateDBSnapshotList");
        try {
            String marker = null;

            do {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DESCRIBE_DB_SNAPSHOTS);
                EC2Method method;
                NodeList blocks;
                Document doc;

                if( marker != null ) {
                    parameters.put("Marker", marker);
                }
                if( snapshotId != null ) {
                    parameters.put("DBSnapshotIdentifier", snapshotId);
                }
                if( databaseId != null ) {
                    parameters.put("DBInstanceIdentifier", databaseId);
                }
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    doc = method.invoke();
                }
                catch( EC2Exception e ) {
                    String code = e.getCode();

                    if( code != null && code.equals("DBSnapshotNotFound") ) {
                        return;
                    }
                    throw new CloudException(e);
                }
                marker = null;
                blocks = doc.getElementsByTagName("Marker");
                if( blocks.getLength() > 0 ) {
                    for( int i=0; i<blocks.getLength(); i++ ) {
                        Node item = blocks.item(i);

                        if( item.hasChildNodes() ) {
                            marker = item.getFirstChild().getNodeValue().trim();
                        }
                    }
                    if( marker != null ) {
                        break;
                    }
                }
                blocks = doc.getElementsByTagName("DBSnapshots");
                for( int i=0; i<blocks.getLength(); i++ ) {
                    NodeList items = blocks.item(i).getChildNodes();

                    for( int j=0; j<items.getLength(); j++ ) {
                        Node item = items.item(j);

                        if( item.getNodeName().equals("DBSnapshot") ) {
                            DatabaseSnapshot snapshot = toSnapshot(item);

                            if( snapshot != null ) {
                                iterator.push(snapshot);
                            }
                        }
                    }
                }
            } while( marker != null );
        }
        finally {
            APITrace.end();
        }
    }
    
    public void removeConfiguration(String providerConfigurationId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.removeConfiguration");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DELETE_DB_PARAMETER_GROUP);
            EC2Method method;

            parameters.put("DBParameterGroupName", providerConfigurationId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private void removeSecurityGroup(String securityGroupId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.removeSecurityGroup");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DELETE_DB_SECURITY_GROUP);
            EC2Method method;

            parameters.put("DBSecurityGroupName", securityGroupId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void removeDatabase(String providerDatabaseId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.removeDatabase");
        try {
            long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE*10L);
            Database db = getDatabase(providerDatabaseId);

            while( timeout > System.currentTimeMillis() ) {
                if( db == null || DatabaseState.DELETED.equals(db.getCurrentState()) ) {
                    return;
                }
                if( DatabaseState.AVAILABLE.equals(db.getCurrentState()) ) {
                    break;
                }
                try { Thread.sleep(15000L); }
                catch( InterruptedException ignore ) { }
                try { db = getDatabase(providerDatabaseId); }
                catch( Throwable ignore ) { }
            }
            Iterable<String> securityGroups = getSecurityGroups(providerDatabaseId);

            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DELETE_DB_INSTANCE);
            EC2Method method;

            parameters.put("DBInstanceIdentifier", providerDatabaseId);
            parameters.put("FinalDBSnapshotIdentifier", providerDatabaseId + "-FINAL-" + System.currentTimeMillis());
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                parameters = provider.getStandardRdsParameters(provider.getContext(), DELETE_DB_INSTANCE);
                parameters.put("DBInstanceIdentifier", providerDatabaseId);
                parameters.put("SkipFinalSnapshot", "true");
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    method.invoke();
                }
                catch( EC2Exception again ) {
                    throw new CloudException(e);
                }
            }
            try {
                for( String securityGroupId : securityGroups ) {
                    if( securityGroupId.equals(providerDatabaseId) ) {
                        try {
                            removeSecurityGroup(securityGroupId);
                        }
                        catch( CloudException ignore ) {
                            // ignore this because it means it is a shared security group
                        }
                    }
                }
            }
            catch( Throwable t ) {
                t.printStackTrace();
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void removeSnapshot(String providerSnapshotId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.removeSnapshot");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), DELETE_DB_SNAPSHOT);
            EC2Method method;

            parameters.put("DBSnapshotIdentifier", providerSnapshotId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void resetConfiguration(String providerConfigurationId, String ... params) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.resetConfiguration");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), RESET_DB_PARAMETER_GROUP);
            EC2Method method;

            parameters.put("DBParameterGroupName", providerConfigurationId);
            if( params == null || params.length < 1 ) {
                parameters.put("ResetAllParameters", "True");
            }
            else {
                int i = 0;

                parameters.put("ResetAllParameters", "False");
                for( String param : params ) {
                    i++;
                    parameters.put("Parameters.member." + i + ".ParameterName", param);
                    parameters.put("Parameters.member." + i + ".ApplyMethod", "pending-reboot");
                }
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void restart(String providerDatabaseId, boolean blockUntilDone) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.restart");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), REBOOT_DB_INSTANCE);
            EC2Method method;

            parameters.put("DBInstanceIdentifier", providerDatabaseId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void revokeAccess(String providerDatabaseId, String sourceCidr) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.revokeAccess");
        try {
            EC2Exception error = null;

            for( String securityGroupId : getSecurityGroups(providerDatabaseId) ) {
                Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), REVOKE_DB_SECURITY_GROUP_INGRESS);
                EC2Method method;

                parameters.put("DBSecurityGroupName", securityGroupId);
                parameters.put("CIDRIP", sourceCidr);
                method = new EC2Method(provider, getRDSUrl(), parameters);
                try {
                    method.invoke();
                }
                catch( EC2Exception e ) {
                    error = e;
                }
            }
            if( error != null ) {
                throw new CloudException(error);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public void updateConfiguration(String providerConfigurationId, ConfigurationParameter ... params) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.updateConfiguration");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), MODIFY_DB_PARAMETER_GROUP);
            EC2Method method;

            parameters.put("DBParameterGroupName", providerConfigurationId);
            int i = 0;
            for( ConfigurationParameter param : params ) {
                i++;
                parameters.put("Parameters.member." + i + ".ParameterName", param.getKey());
                parameters.put("Parameters.member." + i + ".ParameterValue", param.getParameter().toString());
                parameters.put("Parameters.member." + i + ".ApplyMethod", param.isApplyImmediately() ? "immediate" : "pending-reboot");
                if( i >= 20 ) {
                    break;
                }
            }
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            if( params.length > 20 ) {
                ConfigurationParameter[] repeat = new ConfigurationParameter[params.length-20];

                i = 0;
                for( ; i<repeat.length; i++ ) {
                    repeat[i] = params[i+20];
                }
                updateConfiguration(providerConfigurationId, params);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    private void setSecurityGroup(String id, String securityGroupId) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.setSecurityGroup");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), MODIFY_DB_INSTANCE);
            EC2Method method;

            parameters.put("DBInstanceIdentifier", id);
            parameters.put("ApplyImmediately", "true");
            parameters.put("DBSecurityGroups.member.1", securityGroupId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
        }
        finally {
            APITrace.end();
        }
    }
    
    public DatabaseSnapshot snapshot(String providerDatabaseId, String name) throws CloudException, InternalException {
        APITrace.begin(provider, "RDBMS.snapshot");
        try {
            Map<String,String> parameters = provider.getStandardRdsParameters(provider.getContext(), CREATE_DB_SNAPSHOT);
            String id = toIdentifier(name);
            EC2Method method;

            parameters.put("DBSnapshotIdentifier", id);
            parameters.put("DBInstanceIdentifier", providerDatabaseId);
            method = new EC2Method(provider, getRDSUrl(), parameters);
            try {
                method.invoke();
            }
            catch( EC2Exception e ) {
                throw new CloudException(e);
            }
            return getSnapshot(id);
        }
        finally {
            APITrace.end();
        }
    }
    
    private DatabaseConfiguration toConfiguration(Node cfgNode) throws CloudException {
        String id = null, cfgName = null, description = null;
        DatabaseEngine engine = DatabaseEngine.MYSQL; //default
        NodeList attrs = cfgNode.getChildNodes();

        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;
            
            name = attr.getNodeName();
            if( name.equalsIgnoreCase("DBParameterGroupName") ) {
                id = attr.getFirstChild().getNodeValue().trim();
                cfgName = id;
            }
            else if( name.equalsIgnoreCase("Description") ) {
                description = attr.getFirstChild().getNodeValue().trim();                
            }
            else if( name.equals("Engine") ) {
                String awsEngineString = attr.getFirstChild().getNodeValue().trim().toLowerCase();
                engine = toDatabaseEngine(awsEngineString);
            }
        }
        if( id == null || cfgName == null ) {
            return null;
        }
        if( description == null ) {
            description = cfgName;
        }
        return new DatabaseConfiguration(this, engine, id, cfgName, description);
    }

    private DatabaseEngine toDatabaseEngine(String awsEngineString) {
        DatabaseEngine engine = DatabaseEngine.MYSQL;
        if( awsEngineString.equalsIgnoreCase(AWS_ENGINE_ORACLE_SE1) ) {
            engine = DatabaseEngine.ORACLE_SE1;
        }
        else if( awsEngineString.equalsIgnoreCase(AWS_ENGINE_ORACLE_SE) ) {
            engine = DatabaseEngine.ORACLE_SE;
        }
        else if( awsEngineString.equalsIgnoreCase(AWS_ENGINE_ORACLE_EE) ) {
            engine = DatabaseEngine.ORACLE_EE;
        }
        else if( awsEngineString.equalsIgnoreCase(AWS_ENGINE_POSTGRES) ) {
            engine = DatabaseEngine.POSTGRES;
        }
        else if( awsEngineString.equalsIgnoreCase(AWS_ENGINE_SQLSERVER_EE)) {
            engine = DatabaseEngine.SQLSERVER_EE;
        }
        else if ( awsEngineString.equalsIgnoreCase(AWS_ENGINE_SQLSERVER_EX)) {
            engine = DatabaseEngine.SQLSERVER_EX;
        }
        else if ( awsEngineString.equalsIgnoreCase(AWS_ENGINE_SQLSERVER_SE)) {
            engine = DatabaseEngine.SQLSERVER_SE;
        }
        else if ( awsEngineString.equalsIgnoreCase(AWS_ENGINE_SQLSERVER_WEB)) {
            engine = DatabaseEngine.SQLSERVER_WEB;
        }
        return engine;
    }

    private Database toDatabase(Node dbNode) throws CloudException {
        NodeList attrs = dbNode.getChildNodes();
        Database db = new Database();
        String engineVersion = null;
        
        db.setCreationTimestamp(0L);
        db.setProviderRegionId(provider.getContext().getRegionId());
        db.setProviderOwnerId(provider.getContext().getAccountNumber());
        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;
            
            name = attr.getNodeName();
            if( name.equalsIgnoreCase("EngineVersion") ) {
                String val = attr.getFirstChild().getNodeValue().trim();
                
                if( val != null ) {
                    // TODO: do we need engine version in Database?
                    engineVersion = val.toLowerCase().trim();
                }
            }
            else if( name.equalsIgnoreCase("Engine") ) {
                String val = attr.getFirstChild().getNodeValue().trim();
                DatabaseEngine engine = toDatabaseEngine(val);
                db.setEngine(engine);
            }
            else if(name.equalsIgnoreCase("DBInstanceIdentifier") ) {
                db.setProviderDatabaseId(attr.getFirstChild().getNodeValue().trim());
            }
            else if(name.equalsIgnoreCase("DBName")) {
                db.setName(AWSCloud.getTextValue(attr));
            }
            else if( name.equalsIgnoreCase("MultiAZ") ) {
                db.setHighAvailability(attr.getFirstChild().getNodeValue().equalsIgnoreCase("true"));
            }
            else if( name.equalsIgnoreCase("DBInstanceClass") ) {
                db.setProductSize(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("DBInstanceStatus") ) {
                db.setCurrentState(toDatabaseState(attr.getFirstChild().getNodeValue().trim()));
            }
            else if( name.equalsIgnoreCase("Endpoint") ) {
                if( attr.hasChildNodes() ) {
                    NodeList nodes = attr.getChildNodes();
                    
                    for( int j=0; j<nodes.getLength(); j++ ) {
                        Node child = nodes.item(j);
                        
                        if( child != null ) {
                            if( child.getNodeName().equalsIgnoreCase("Port") ) {
                                try {
                                    db.setHostPort(Integer.parseInt(child.getFirstChild().getNodeValue().trim()));
                                }
                                catch( NumberFormatException e ) {
                                    throw new CloudException("Invalid storage value: " + child.getFirstChild().getNodeValue());                                    // ignore this
                                }
                            }
                            else if( child.getNodeName().equalsIgnoreCase("Address") ) {
                                db.setHostName(child.getFirstChild().getNodeValue().trim());
                            }
                        }
                    }
                }
            }
            else if( name.equalsIgnoreCase("AllocatedStorage") ) {
                try {
                    db.setAllocatedStorageInGb(Integer.parseInt(attr.getFirstChild().getNodeValue().trim()));
                }
                catch( NumberFormatException e ) {
                    throw new CloudException("Invalid storage value: " + attr.getFirstChild().getNodeValue());
                }
            }
            else if( name.equalsIgnoreCase("MasterUsername") ) {
                db.setAdminUser(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("PreferredMaintenanceWindow") ) {
                if( attr.hasChildNodes() ) {
                    String val = attr.getFirstChild().getNodeValue();
                
                    if( val != null ) {
                        String[] parts = val.split("-");
                        
                        if( parts.length == 2 ) {
                            TimeWindow window = getTimeWindow(parts[0], parts[1]);

                            db.setMaintenanceWindow(window);
                        }
                    }
                }
            }
            else if( name.equalsIgnoreCase("PreferredBackupWindow") ) {
                if( attr.hasChildNodes() ) {
                    String val = attr.getFirstChild().getNodeValue();
                
                    if( val != null ) {
                        String[] parts = val.split("-");
                        
                        if( parts.length == 2 ) {
                            TimeWindow window = getTimeWindow(parts[0], parts[1]);

                            db.setSnapshotWindow(window);
                        }
                    }
                }
            }
            else if( name.equalsIgnoreCase("AvailabilityZone") ) {
                db.setProviderDataCenterId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("InstanceCreateTime") ) {
                String tstr = attr.getFirstChild().getNodeValue().trim();
                
                db.setCreationTimestamp(provider.parseTime(tstr));
            }
            else if( name.equalsIgnoreCase("LatestRestorableTime") ) {               
                db.setRecoveryPointTimestamp(provider.parseTime(attr.getFirstChild().getNodeValue().trim()));
            }
            else if( name.equalsIgnoreCase("BackupRetentionPeriod") ) {
                try {
                    db.setSnapshotRetentionInDays(Integer.parseInt(attr.getFirstChild().getNodeValue().trim()));
                }
                catch( NumberFormatException e ) {
                    throw new CloudException("Invalid backup retention period: " + attr.getFirstChild().getNodeValue());
                }
            }
            else if( name.equalsIgnoreCase("DBParameterGroups") ) {
                NodeList groups = attr.getChildNodes();
                
                for( int j=0; j<groups.getLength(); j++ ) {
                    Node group = groups.item(j);
                    
                    if( group.getNodeName().equalsIgnoreCase("DBParameterGroup") && group.hasChildNodes() ) {
                        NodeList nodes = group.getChildNodes();
                            
                        for( int k=0; k<nodes.getLength(); k++ ) {
                            Node child = nodes.item(k);
                                
                            if( child != null ) {
                                if( child.getNodeName().equalsIgnoreCase("DBParameterGroupName") ) {
                                    db.setConfiguration(child.getFirstChild().getNodeValue().trim());
                                }
                            }
                        }                        
                    }
                }
            }
        }
        if( db.getHostName() == null ) {
            if( db.getCurrentState().equals(DatabaseState.PENDING) || db.getCurrentState().equals(DatabaseState.MODIFYING) || db.getCurrentState().equals(DatabaseState.RESTARTING) ) {
                db.setHostName("");
            }
            else {
                System.out.println("DEBUG: null database for " + db.getCurrentState());
                return null;
            }
        }
        if( db.getName() == null ) {
            db.setName(db.getProviderDatabaseId());
        }
        return db;
    }

    private @Nullable ResourceStatus toDatabaseStatus(@Nullable Node dbNode) throws CloudException {
        if( dbNode == null ) {
            return null;
        }
        NodeList attrs = dbNode.getChildNodes();
        DatabaseState state = DatabaseState.PENDING;
        String dbId = null;

        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;

            name = attr.getNodeName();
            if(name.equalsIgnoreCase("DBInstanceIdentifier") ) {
                dbId = attr.getFirstChild().getNodeValue().trim();
            }
            else if( name.equalsIgnoreCase("DBInstanceStatus") ) {
                state = toDatabaseState(attr.getFirstChild().getNodeValue().trim());
            }
        }
        if( dbId == null ) {
            return null;
        }
        return new ResourceStatus(dbId, state);
    }

    private DatabaseState toDatabaseState(String value) throws CloudException {
        //incompatible-option-group, incompatible-parameters, incompatible-restore, incompatible-network
        if( value == null ) {
            System.out.println("DEBUG: Null state value");
            return DatabaseState.PENDING;
        }
        else if( value.equalsIgnoreCase("available") || value.equals("incompatible-option-group") || value.equals("incompatible-parameters") || value.equals("incompatible-restore") || value.equals("incompatible-network") ) {
            return DatabaseState.AVAILABLE;
        }
        else if( value.equalsIgnoreCase("storage-full") ) {
            return DatabaseState.STORAGE_FULL;
        }
        else if( value.equalsIgnoreCase("failed") ) {
            return DatabaseState.FAILED;
        }
        else if( value.equalsIgnoreCase("backing-up") ) {
            return DatabaseState.BACKUP;
        }
        else if( value.equalsIgnoreCase("creating") ) {
            return DatabaseState.PENDING;
        }
        else if( value.equalsIgnoreCase("deleted") ) {
            return DatabaseState.DELETED;
        }
        else if( value.equalsIgnoreCase("deleting") ) {
            return DatabaseState.DELETING;
        }
        else if( value.equalsIgnoreCase("modifying") ) {
            return DatabaseState.MODIFYING;
        }
        else if( value.equalsIgnoreCase("rebooting") ) {
            return DatabaseState.RESTARTING;
        }
        else if( value.equalsIgnoreCase("resetting-mastercredentials") ) {
            return DatabaseState.MODIFYING;
        }
        else {
            System.out.println("DEBUG: Unknown database state: " + value);
            return DatabaseState.PENDING;
        }
    }
    
    private String toIdentifier(String rawName) {
        StringBuilder str = new StringBuilder();
        
        if( rawName.length() < 1 ) {
            return "x";
        }
        if( !Character.isLetter(rawName.charAt(0)) ) {
            rawName = "db-" + rawName;
        }
        char last = '\0';
        for( int i=0; i<rawName.length(); i++ ) {
            char c = rawName.charAt(i);
            
            if( Character.isLetterOrDigit(c) ) {
                str.append(c);
            }
            if( c == '-' && last != '-' ) {
                str.append(c);
            }
            last = c;
        }
        rawName = str.toString();
        if( rawName.length() > 63 ) {
            rawName = rawName.substring(0,63);
        }
        while( rawName.charAt(rawName.length()-1) == '-' ) {
            rawName = rawName.substring(0,rawName.length()-1);
        }
        if( rawName.length() < 1 ) {
            return "x";
        }
        return rawName;
    }
    
    private ConfigurationParameter toParameter(Node pNode) throws CloudException {
        ConfigurationParameter param = new ConfigurationParameter();
        NodeList attrs = pNode.getChildNodes();
        
        param.setModifiable(false);
        param.setApplyImmediately(false);
        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;
            
            name = attr.getNodeName();
            if( name.equalsIgnoreCase("ParameterValue") ) {
                param.setParameter(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("DataType") ) {
                param.setDataType(attr.getFirstChild().getNodeValue().trim());                
            }
            else if( name.equalsIgnoreCase("IsModifiable") && attr.hasChildNodes() ) {
                param.setModifiable(attr.getFirstChild().getNodeValue().trim().equalsIgnoreCase("true"));
            }
            else if( name.equalsIgnoreCase("Description") ) {
                param.setDescription(attr.getFirstChild().getNodeValue().trim());                
            }
            else if( name.equalsIgnoreCase("AllowedValues") && attr.hasChildNodes() ) {
                param.setValidation(attr.getFirstChild().getNodeValue().trim());                
            }
            else if( name.equalsIgnoreCase("ParameterName") ) {
                param.setKey(attr.getFirstChild().getNodeValue().trim());                
            }
            else if( name.equalsIgnoreCase("ApplyType") && attr.hasChildNodes() ) {
                param.setApplyImmediately(attr.getFirstChild().getNodeValue().trim().equalsIgnoreCase("static"));
            }
        }
        return param;
    }

    private DatabaseSnapshot toSnapshot(Node snapshotNode) throws CloudException {
        DatabaseSnapshot snapshot = new DatabaseSnapshot();
        NodeList attrs = snapshotNode.getChildNodes();
        
        snapshot.setProviderRegionId(provider.getContext().getRegionId());
        snapshot.setProviderOwnerId(provider.getContext().getAccountNumber());
        for( int i=0; i<attrs.getLength(); i++ ) {
            Node attr = attrs.item(i);
            String name;
            
            name = attr.getNodeName();
            if( name.equalsIgnoreCase("SnapshotCreateTime") ) {
                snapshot.setSnapshotTimestamp(provider.parseTime(attr.getFirstChild().getNodeValue().trim()));
            }
            else if( name.equalsIgnoreCase("Status") ) {
                String value = attr.getFirstChild().getNodeValue().trim();
                DatabaseSnapshotState state;
                
                if( value.equalsIgnoreCase("available") ) {
                    state = DatabaseSnapshotState.AVAILABLE;
                }
                else if( value.equalsIgnoreCase("creating") ) {
                    state = DatabaseSnapshotState.CREATING;
                }
                else if( value.equalsIgnoreCase("deleting") ) {
                    state = DatabaseSnapshotState.DELETING;
                }
                else {
                    throw new CloudException("Unknown database snapshot state: " + value);
                }
                snapshot.setCurrentState(state);
            }
            else if( name.equalsIgnoreCase("MasterUsername") ) {
                snapshot.setAdminUser(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("DBInstanceIdentifier") ){
                snapshot.setProviderDatabaseId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("DBSnapshotIdentifier") ) {
                snapshot.setProviderSnapshotId(attr.getFirstChild().getNodeValue().trim());
            }
            else if( name.equalsIgnoreCase("AllocatedStorage") ) {
                snapshot.setStorageInGigabytes(Integer.parseInt(attr.getFirstChild().getNodeValue().trim()));
            }
        }
        return snapshot;
    }
}
