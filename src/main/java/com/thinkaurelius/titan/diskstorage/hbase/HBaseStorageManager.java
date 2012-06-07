package com.thinkaurelius.titan.diskstorage.hbase;

import java.io.IOException;
import java.util.Iterator;

import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableExistsException;
import org.apache.hadoop.hbase.TableNotFoundException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thinkaurelius.titan.core.GraphDatabaseException;
import com.thinkaurelius.titan.core.GraphStorageException;
import com.thinkaurelius.titan.diskstorage.OrderedKeyColumnValueStore;
import com.thinkaurelius.titan.diskstorage.StorageManager;
import com.thinkaurelius.titan.diskstorage.TransactionHandle;
import com.thinkaurelius.titan.diskstorage.locking.LocalLockMediator;
import com.thinkaurelius.titan.diskstorage.locking.LocalLockMediators;
import com.thinkaurelius.titan.diskstorage.util.ConfigHelper;
import com.thinkaurelius.titan.diskstorage.util.OrderedKeyColumnValueIDManager;
import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;

/**
 * Experimental storage manager for HBase.
 * 
 * This is not ready for production.
 * 
 * @author Dan LaRocque <dalaro@hopcount.org>
 */
public class HBaseStorageManager implements StorageManager {

	private static final Logger log = LoggerFactory.getLogger(HBaseStorageManager.class);
	
    static final String TABLE_NAME_KEY = "tablename";
    static final String TABLE_NAME_DEFAULT = "titan";
    
    public static final String LOCAL_LOCK_MEDIATOR_PREFIX_KEY = "local-lock-mediator-prefix";
    public static final String LOCAL_LOCK_MEDIATOR_PREFIX_DEFAULT = "hbase";

    public static final String PROP_HBASE_KEY = "hbconf";


	private final String tableName;
    private final OrderedKeyColumnValueIDManager idmanager;
    private final int lockRetryCount;
    
    private final long lockWaitMS, lockExpireMS;
    
    private final byte[] rid;
    
    private final String llmPrefix;
    
    private final org.apache.hadoop.conf.Configuration hconf;
	
    public HBaseStorageManager(org.apache.commons.configuration.Configuration config) {
    	this.rid = ConfigHelper.getRid(config);
    	
        this.tableName = config.getString(TABLE_NAME_KEY,TABLE_NAME_DEFAULT);
		
        this.llmPrefix =
				config.getString(
						LOCAL_LOCK_MEDIATOR_PREFIX_KEY,
						LOCAL_LOCK_MEDIATOR_PREFIX_DEFAULT);
        
		this.lockRetryCount =
				config.getInt(
						GraphDatabaseConfiguration.LOCK_RETRY_COUNT,
						GraphDatabaseConfiguration.LOCK_RETRY_COUNT_DEFAULT);
		
		this.lockWaitMS =
				config.getLong(
						GraphDatabaseConfiguration.LOCK_WAIT_MS,
						GraphDatabaseConfiguration.LOCK_WAIT_MS_DEFAULT);
		
		this.lockExpireMS =
				config.getLong(
						GraphDatabaseConfiguration.LOCK_EXPIRE_MS,
						GraphDatabaseConfiguration.LOCK_EXPIRE_MS_DEFAULT);
		
		// Copy a subset of our commons config into a Hadoop config
		org.apache.commons.configuration.Configuration hbCommons =
			config.subset(PROP_HBASE_KEY);
		@SuppressWarnings("unchecked") // I hope commons-config eventually fixes this
		Iterator<String> keys = hbCommons.getKeys();
		
		int keysLoaded = 0;

		this.hconf = HBaseConfiguration.create();
		
		while (keys.hasNext()) {
			String k = keys.next();
			String v = hbCommons.getString(k);
			
			log.debug("HBase configuration: setting {}={}", k, v);
			
			hconf.set(k, v);
			
			keysLoaded++;
		}
		
		log.debug("HBase configuration: set a total of {} configuration values", keysLoaded);
		
        idmanager = new OrderedKeyColumnValueIDManager(
        		openDatabase("blocks_allocated", null, null), rid, config);
    }


    @Override
    public long[] getIDBlock(int partition) {
        return idmanager.getIDBlock(partition);
    }

	@Override
	public OrderedKeyColumnValueStore openDatabase(String name)
			throws GraphStorageException {
		
		OrderedKeyColumnValueStore lockStore =
				openDatabase(name + "_locks", null, null);
		LocalLockMediator llm = LocalLockMediators.INSTANCE.get(llmPrefix + ":" + name);
		OrderedKeyColumnValueStore dataStore =
				openDatabase(name, llm, lockStore);
		
		return dataStore;
	}
	
	private OrderedKeyColumnValueStore openDatabase(String name, LocalLockMediator llm, OrderedKeyColumnValueStore lockStore)
			throws GraphStorageException {
		
		HBaseAdmin adm = null;
		try {
			adm = new HBaseAdmin(hconf);
		} catch (IOException e) {
			throw new GraphDatabaseException(e);
		}
		
		// Create our table, if necessary
		HTableDescriptor desc = null;
		try {
		    desc = new HTableDescriptor(tableName);
			adm.createTable(desc);
		} catch (TableExistsException e) {
			try {
				desc = adm.getTableDescriptor(tableName.getBytes());
			} catch (IOException ee) {
				throw new GraphStorageException(ee);
			}
		} catch (IOException e) {
			throw new GraphStorageException(e);
		}
		
		assert null != desc;
		
		// Create our column family, if necessary
		if (null == desc.getFamily(name.getBytes())) {
			try {
				adm.disableTable(tableName);
				desc.addFamily(new HColumnDescriptor(name));
				adm.modifyTable(tableName.getBytes(), desc);
				log.debug("Added HBase column family {}", name);
				try {
					Thread.sleep(5000L);
				} catch (InterruptedException ie) {
					throw new GraphStorageException(ie);
				}
				adm.enableTable(tableName);
			} catch (TableNotFoundException ee) {
				log.error("TableNotFoundException", ee);
				throw new GraphStorageException(ee);
			} catch (org.apache.hadoop.hbase.TableExistsException ee) {
				log.debug("Swallowing exception {}", ee);
			} catch (IOException ee) {
				throw new GraphStorageException(ee);
			}
		}
			
		assert null != desc;
		
		// Retrieve an object to interact with our now-initialized table
//		HTable table;
//		try {
//			table = new HTable(conf, tableName);
//		} catch (IOException e) {
//			throw new GraphStorageException(e);
//		}
		
		return new HBaseOrderedKeyColumnValueStore(hconf, tableName, name, lockStore,
				llm, rid, lockRetryCount, lockWaitMS, lockExpireMS);
	}

	@Override
	public TransactionHandle beginTransaction() {
		return new HBaseTransaction();
	}

	@Override
	public void close() {
		//Nothing to do
	}



}