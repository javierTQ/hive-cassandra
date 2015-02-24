hive-cassandra
==============

Fork of https://github.com/2013Commons/hive-cassandra that fix project to read from a cassandra table using hive 0.14 external table.


==============


<ol>
<li>Create in cassandra a keyspace and a table with some data:</li>

CREATE KEYSPACE bigdata WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 } ;

use bigdata ;

CREATE TABLE test (key int PRIMARY KEY, data text);

INSERT INTO test (key, data ) VALUES ( 1, 'This data can be read automatically in hive');

SELECT * FROM test;

<li>Install the project:</li>

	a. Git project and compile it.

	b. Add those jars to hive classpath.

Those jar are : 
cassandra-all-2.0.4.jar, 
cassandra-thrift-2.0.4.jar,
hive-0.14.0-hadoop-2.0.0-cassandra-2.0-0.0.1.jar,

whick are in the project/target and project/target/dependency.

<li>Create a database:</li>

CREATE DATABASE bigdata;\n

use bigdata;\n

<li>Create a external table in hive and read the information contain in cassandra:<li>

CREATE EXTERNAL TABLE hivetable (key int, data string) STORED BY 'org.apache.hadoop.hive.cassandra.cql.CqlStorageHandler' TBLPROPERTIES ("cassandra.ks.name" = "bigdata", "cassandra.cf.name" = "test");\n

SELECT * FROM hivetable;\n

</ol>

========================================================================================================================
References:

Browsing through Cassandra tables in Hive:
http://www.datastax.com/documentation/datastax_enterprise/4.6/datastax_enterprise/ana/anaHivBrowse.html

Creating a custom external table:
http://www.datastax.com/documentation/datastax_enterprise/4.6/datastax_enterprise/ana/anaHivCreatCql.html
