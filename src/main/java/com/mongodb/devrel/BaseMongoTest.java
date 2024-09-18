package com.mongodb.devrel;

import org.bson.Document;

import com.mongodb.client.MongoClient;

public class BaseMongoTest implements Runnable {
    protected MongoClient mongoClient;
    protected Document testConfig;
    
    BaseMongoTest(MongoClient client, Document config ) {
        this.mongoClient = client;
        this.testConfig = config;
    }

    public   void GenerateData() {
        throw new UnsupportedOperationException("Unimplemented method 'GenerateData'");
    }

    public  void WarmCache() {
        return;
    }

    public  void TestReset() {
        return;
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("Unimplemented method 'run'");
    }
}
