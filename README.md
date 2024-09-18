
#To Setup AWS Host as a Client

#Launch instance at least as much CPU as database server

sudo yum install -y java-21 git maven

git clone https://github.com/johnlpage/LinkTestMany.git

export JAVA_HOME="/usr/lib/jvm/java-21-amazon-corretto"
cd LinkTestMany
mvn clean package

export MONGO_URI="... YOUR URI & CREDS "
java -jar bin/CoreMongoTest.jar <config.json>