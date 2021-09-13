CREATE KEYSPACE IF NOT EXISTS {{keyspace}} WITH REPLICATION = { 'class' : 'SimpleStrategy','replication_factor':1 };
CREATE KEYSPACE IF NOT EXISTS {{keyspace}}_snapshot WITH REPLICATION = { 'class' : 'SimpleStrategy','replication_factor':1 };
