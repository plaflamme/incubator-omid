/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.yahoo.omid.notifications;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

public class ScannerContainer {

    private static final Logger logger = Logger.getLogger(ScannerContainer.class);

    private static final long TIMEOUT = 3;
    private static final TimeUnit UNIT = TimeUnit.SECONDS;

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private String interest;
    private Map<String, List<String>> interestsToObservers;
    private Map<String, List<String>> observersToHosts;
    private HTable table;
    
    /**
     * @param interest
     */
    public ScannerContainer(String interest, Map<String, List<String>> interestsToObservers, Map<String, List<String>> observersToHosts) {
        this.interest = interest;
        this.interestsToObservers = interestsToObservers;
        this.observersToHosts = observersToHosts;
        

        // Generate scaffolding on HBase to maintain the information required to perform notifications
        Configuration config = HBaseConfiguration.create();
        HBaseAdmin admin;
        try {
            Interest interestRep = Interest.fromString(this.interest);
            admin = new HBaseAdmin(config);
            HTableDescriptor tableDesc = admin.getTableDescriptor(interestRep.getTableAsHBaseByteArray());
            String notificationsMetaCF = interestRep.getColumnFamily() + Constants.NOTIF_HBASE_CF_SUFFIX;
            if(!tableDesc.hasFamily(Bytes.toBytes(notificationsMetaCF))) {                
                String tableName = interestRep.getTable();
        
                admin.disableTable(tableName);
        
                HColumnDescriptor cf1 = new HColumnDescriptor(notificationsMetaCF);
                admin.addColumn(tableName, cf1);      // adding new ColumnFamily
        
                Map<String, String> params = new HashMap<String, String>();
                // TODO I think that coprocessors can not be added dynamically. It has been moved to OmidInfrastructure
                //tableDesc.addCoprocessor(TransactionCommittedRegionObserver.class.getName(), null, Coprocessor.PRIORITY_USER, params);
                admin.enableTable(tableName);
                logger.trace("Column family metadata added!!!");
            } else {
                logger.trace("Column family metadata was already added!!! Skipping...");
            }
            table = new HTable(config,interestRep.getTable());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // TSO Client setup

    }

    public void start()
            throws Exception {
        // TODO Start the number of required scanners instead of only one
        exec.execute(new Scanner(table, interest, interestsToObservers, observersToHosts));
        logger.trace("Scanners on " + interest + " started");
    }

    public void stop() throws InterruptedException {
        try {
            table.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        exec.shutdownNow();
        exec.awaitTermination(TIMEOUT, UNIT);
        logger.trace("Scanners on " + interest + " stopped");
    }

}