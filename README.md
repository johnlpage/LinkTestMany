Create an Atlas Cluster to use - for any Benchmarkin an M30 is a miniumum as it has dedicated resources.
Get an EC2 instance close to it



install mongosh

Create a `/etc/yum.repos.d/mongodb-org-7.0.repo` file so that you can install mongosh directly using yum.

```
[mongodb-org-7.0]
name=MongoDB Repository
baseurl=https://repo.mongodb.org/yum/amazon/2023/mongodb-org/7.0/$basearch/
gpgcheck=1
enabled=1
gpgkey=https://www.mongodb.org/static/pgp/server-7.0.asc
```

then 

```
sudo yum install -y mongodb-mongosh-shared-openssl3
```

Load Sample data from the GUI then run gendata.sh


// Download and Build AggSpeedTEst benchmarking tool

```
sudo yum install java
sudo yum install git
 git clone https://github.com/johnlpage/AggSpeedTest.git
sudo wget https://repos.fedorapeople.org/repos/dchen/apache-maven/epel-apache-maven.repo -O /etc/yum.repos.d/epel-apache-maven.repo
sudo sed -i s/\$releasever/6/g /etc/yum.repos.d/epel-apache-maven.repo
sudo yum install -y apache-maven

cd AggSpeedTest
mvn clean package
```


// Test retrieval speed by _id where vectors are included

```
{
    "uri" : "mongodb+srv://USER:PWD@vectortest.eez2n.mongodb.net/?retryWri
tes=true&w=majority&appName=VectorTest",
    "threads" : 20,
    "pipeline" : [ {"$match":{"_id" : "<<VALUE>>"}}, { "$limit" : 1 }],
    "database" : "sample_mflix",
    "collection" : "movies_with_embed",
    "calls" : 1000000,
    "query_values" : {
          "uri" : "mongodb+srv://USER:PWD@vectortest.eez2n.mongodb.net/?retryWri
tes=true&w=majority&appName=VectorTest",
       "database" : "sample_mflix",
       "collection" : "movies_only",
       "pipeline": [{"$match":{"_id":{"$ne":null}}},{"$project":{"_id":1}}],
       "field" : "_id"
    } 
}
13:20:11.616 [main] INFO  com.mongodb.devrel.AggSpeedTest - 88 - Time: 1030913 ms Operations/s = 683

```
// Test retrieval speed by _id where vectors are not included

{
    "uri" : "mongodb+srv://USER:PWD@vectortest.eez2n.mongodb.net/?retryWri
tes=true&w=majority&appName=VectorTest",
    "threads" : 20,
    "pipeline" : [ {"$match":{"_id" : "<<VALUE>>"}}, { "$limit" : 1 }],
    "database" : "sample_mflix",
    "collection" : "movies_only",
    "calls" : 1000000,
    "query_values" : {
          "uri" : "mongodb+srv://USER:PWD@vectortest.eez2n.mongodb.net/?retryWri
tes=true&w=majority&appName=VectorTest",
       "database" : "sample_mflix",
       "collection" : "movies_only",
       "pipeline": [{"$match":{"_id":{"$ne":null}}},{"$project":{"_id":1}}],
       "field" : "_id"
    } 
}

15:31:03.669 [main] INFO  com.mongodb.devrel.AggSpeedTest - 88 - Time: 638740 ms Operations/s = 7827

```





// Test retrieval speed by vector where vectors are inline

// Test retrieval speed by vector where vectors are not inline