hive-cassandra
==============

Fork of https://github.com/2013Commons/hive-cassandra that fix project to read from a cassandra table using hive 0.14 external table.

<b>NOTE:</b> Work in progress. Currently not works when the query generate a mapreduce job. 

==============


<ol>
<li>Create in cassandra a keyspace and a table with some data:</li>

CREATE KEYSPACE bigdata WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 } ; </br>

use bigdata ;</br>

CREATE TABLE test (key int PRIMARY KEY, data text);</br>

INSERT INTO test (key, data ) VALUES ( 1, 'This data can be read automatically in hive');</br>

SELECT * FROM test;</br>

<li>Install the project:</li>

	a. Git project and compile it.

	b. Add those jars to hive classpath.

Those jar are : 
cassandra-all-2.0.4.jar, 
cassandra-thrift-2.0.4.jar,
hive-0.14.0-hadoop-2.0.0-cassandra-2.0-0.0.1.jar,

whick are in the project/target and project/target/dependency.

<li>Create a database:</li>

CREATE DATABASE bigdata;</br>

use bigdata;</br>

<li>Create a external table in hive and read the information contain in cassandra:<li>

CREATE EXTERNAL TABLE hivetable (key int, data string) STORED BY 'org.apache.hadoop.hive.cassandra.cql.CqlStorageHandler' TBLPROPERTIES ("cassandra.ks.name" = "bigdata", "cassandra.cf.name" = "test");</br>

SELECT * FROM hivetable;</br>

</ol>

========================================================================================================================
References:

Browsing through Cassandra tables in Hive:
http://www.datastax.com/documentation/datastax_enterprise/4.6/datastax_enterprise/ana/anaHivBrowse.html

Creating a custom external table:
http://www.datastax.com/documentation/datastax_enterprise/4.6/datastax_enterprise/ana/anaHivCreatCql.html
